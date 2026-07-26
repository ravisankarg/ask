const demos = [
  {
    query: "How much was the Odyssey ticket?",
    plan: "[query_category == doc] && [[semantic == Odyssey movie ticket price] + [ocr == Odyssey ticket price total]]",
    shown: "1",
    count: "1"
  },
  {
    query: "What is my passport number?",
    plan: "[query_category == doc] && [[semantic == passport identity document] + [ocr == Ravi passport]]",
    shown: "3",
    count: "3"
  },
  {
    query: "Rvai phots at tem outng",
    plan: "[query_category == scenary] && [[person == Ravi] && [semantic == team outing] && [mime type == photos]]",
    shown: "200",
    count: "1,284"
  },
  {
    query: "Ravi without glasses",
    plan: "[query_category == scenary] && [[[person == Ravi] && [mime type == photos]] - [semantic == glasses]]",
    shown: "200",
    count: "342"
  },
  {
    query: "last Goa trip photos without Ramani",
    plan: "[query_category == scenary] && [[[[location == Goa] && [mime type == photos]] - [person == Ramani]]] SORT_DATE",
    shown: "76",
    count: "76"
  }
];

let demoIndex = 0;
const queryNode = document.querySelector("#demo-query");
const planNode = document.querySelector("#demo-plan");
const countNode = document.querySelector(".result-head span");

document.querySelector("#cycle-demo")?.addEventListener("click", () => {
  demoIndex = (demoIndex + 1) % demos.length;
  const demo = demos[demoIndex];
  [queryNode, planNode].forEach((node) => node?.animate(
    [{ opacity: 1, transform: "translateY(0)" }, { opacity: 0, transform: "translateY(4px)" }],
    { duration: 140, fill: "forwards" }
  ).finished.then(() => {
    queryNode.textContent = demo.query;
    planNode.textContent = demo.plan;
    const label = demo.count === "1" ? "match" : "matches";
    countNode.innerHTML = `<strong>${demo.shown}</strong> of ${demo.count} ${label}`;
    node.animate(
      [{ opacity: 0, transform: "translateY(-4px)" }, { opacity: 1, transform: "translateY(0)" }],
      { duration: 220, fill: "forwards" }
    );
  }));
});

const revealObserver = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) {
      entry.target.classList.add("visible");
      revealObserver.unobserve(entry.target);
    }
  });
}, { threshold: 0.12 });

document.querySelectorAll("[data-reveal]").forEach((node, index) => {
  node.style.transitionDelay = `${Math.min(index % 4, 3) * 55}ms`;
  revealObserver.observe(node);
});

const filterButtons = document.querySelectorAll(".module-filters button");
const moduleCards = document.querySelectorAll(".module-card");
filterButtons.forEach((button) => {
  button.addEventListener("click", () => {
    filterButtons.forEach((item) => item.classList.remove("active"));
    button.classList.add("active");
    const selected = button.dataset.filter;
    moduleCards.forEach((card) => {
      const kinds = card.dataset.kind.split(" ");
      card.classList.toggle("hidden", selected !== "all" && !kinds.includes(selected));
    });
  });
});

const sections = [...document.querySelectorAll("main section[id]")];
const navLinks = [...document.querySelectorAll(".desktop-nav a")];
const navObserver = new IntersectionObserver((entries) => {
  const visible = entries
    .filter((entry) => entry.isIntersecting)
    .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
  if (!visible) return;
  navLinks.forEach((link) => {
    link.classList.toggle("active", link.getAttribute("href") === `#${visible.target.id}`);
  });
}, { rootMargin: "-20% 0px -65% 0px", threshold: [0.05, 0.25] });
sections.forEach((section) => navObserver.observe(section));
