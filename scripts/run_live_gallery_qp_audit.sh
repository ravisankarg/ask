#!/usr/bin/env bash
set -u

corpus_path="${1:-docs/qp_gallery_search_live_100_2026-08-24.tsv}"
live_log_path="${2:-/tmp/ask-galaxy-gallery-qp-live.log}"
start_id="${3:-002}"
end_id="${4:-100}"
device_serial="${5:-}"
adb_cmd=(adb)
if [[ -n "$device_serial" ]]; then
    adb_cmd+=(-s "$device_serial")
fi

count_matches() {
    local pattern="$1"
    if [[ -f "$live_log_path" ]]; then
        rg -c "$pattern" "$live_log_path" 2>/dev/null || true
    else
        printf '0\n'
    fi
}

wait_for_increment() {
    local pattern="$1"
    local baseline="$2"
    local timeout_seconds="$3"
    local elapsed=0
    local current
    while (( elapsed < timeout_seconds * 5 )); do
        current="$(count_matches "$pattern")"
        current="${current:-0}"
        if (( current > baseline )); then
            return 0
        fi
        sleep 0.2
        elapsed=$((elapsed + 1))
    done
    return 1
}

wait_for_terminal_or_process_exit() {
    local pattern="$1"
    local baseline="$2"
    local timeout_seconds="$3"
    local elapsed=0
    local current
    local process_id
    while (( elapsed < timeout_seconds * 5 )); do
        current="$(count_matches "$pattern")"
        current="${current:-0}"
        if (( current > baseline )); then
            return 0
        fi
        process_id="$("${adb_cmd[@]}" shell pidof com.ravi.askgalaxy </dev/null | tr -d '\r')"
        if [[ -z "$process_id" ]]; then
            return 2
        fi
        sleep 0.2
        elapsed=$((elapsed + 1))
    done
    return 1
}

started=0
# Bring the existing task forward once. Ten seconds covers a cold E2B load;
# an already-resident planner simply remains warm. No per-case stop/relaunch.
timeout 12s "${adb_cmd[@]}" shell am start -n com.ravi.askgalaxy/.MainActivity </dev/null >/dev/null || true
sleep 10
process_id="$("${adb_cmd[@]}" shell pidof com.ravi.askgalaxy </dev/null | tr -d '\r')"
if [[ -n "$process_id" ]]; then
    ready_available=1
else
    ready_available=0
fi
while IFS=$'\t' read -r case_id family query expected; do
    [[ "$case_id" == "id" ]] && continue
    if [[ "$started" == "0" ]]; then
        [[ "$case_id" == "$start_id" ]] || continue
        started=1
    fi

    process_id="$("${adb_cmd[@]}" shell pidof com.ravi.askgalaxy </dev/null | tr -d '\r')"
    if [[ -z "$process_id" ]]; then
        ready_before="$(count_matches 'planner preface explicitly prefetched')"
        ready_before="${ready_before:-0}"
        timeout 12s "${adb_cmd[@]}" shell am start -n com.ravi.askgalaxy/.MainActivity </dev/null >/dev/null || true
        if ! wait_for_increment 'planner preface explicitly prefetched' "$ready_before" 45; then
            printf '%s\tWARMUP_TIMEOUT\t%s\n' "$case_id" "$query"
            ready_available=0
            [[ "$case_id" == "$end_id" ]] && break
            continue
        fi
        if [[ -z "$process_id" ]]; then
            printf 'APP_RELAUNCHED\t%s\t%s\n' "$case_id" "$query"
        fi
        ready_available=1
    elif [[ "$ready_available" == "0" ]]; then
        # A completed query can miss the observable prefetch marker even while
        # the resident process remains usable. Foreground it without killing
        # or relaunching, then let the app own planner-session recovery.
        timeout 12s "${adb_cmd[@]}" shell am start -n com.ravi.askgalaxy/.MainActivity </dev/null >/dev/null || true
        sleep 2
        ready_available=1
    fi

    next_ready_before="$(count_matches 'planner preface explicitly prefetched')"
    next_ready_before="${next_ready_before:-0}"
    finished_before="$(count_matches 'Gemma-only V2 plan accepted|Gemma-only query planning failed')"
    finished_before="${finished_before:-0}"
    printf 'AUDIT_START\t%s\t%s\t%s\n' "$(date +%s.%3N)" "$case_id" "$query"
    # Put the case identity into the same live stream as the QP evidence so
    # parsing never depends on timing or a hard-coded retry order.
    "${adb_cmd[@]}" shell log -t AskGalaxyAudit "AUDIT_START__${case_id}__${query}" </dev/null
    # The temporary debug audit action replaces the same EditText and invokes
    # MainActivity.search(). Base64 keeps shell punctuation out of the intent;
    # E2B still authors the complete QP and Kotlin only validates it.
    encoded_query="$(printf '%s' "$query" | base64 -w0)"
    timeout 12s "${adb_cmd[@]}" shell am start \
        -n com.ravi.askgalaxy/.MainActivity \
        -a com.ravi.askgalaxy.DEBUG_SUBMIT_SEARCH \
        -f 0x20000000 \
        --es query_base64 "$encoded_query" </dev/null >/dev/null

    if wait_for_terminal_or_process_exit 'Gemma-only V2 plan accepted|Gemma-only query planning failed' "$finished_before" 90; then
        printf '%s\tCAPTURED\t%s\n' "$case_id" "$query"
    else
        terminal_status=$?
        if [[ "$terminal_status" == "2" ]]; then
            printf '%s\tPROCESS_LOST\t%s\n' "$case_id" "$query"
        else
            printf '%s\tQP_TIMEOUT\t%s\n' "$case_id" "$query"
        fi
    fi
    if wait_for_increment 'planner preface explicitly prefetched' "$next_ready_before" 8; then
        ready_available=1
    else
        ready_available=0
    fi
    # Let result rendering settle so an installed-app crash is observed before
    # the next query instead of being mistaken for a lost keyboard event.
    sleep 1
    [[ "$case_id" == "$end_id" ]] && break
done < "$corpus_path"
