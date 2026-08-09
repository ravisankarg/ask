const reveal = document.querySelectorAll(".reveal");

if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("visible");
      observer.unobserve(entry.target);
    });
  }, { threshold: 0.12 });
  reveal.forEach((node, index) => {
    node.style.transitionDelay = `${Math.min(index % 5, 4) * 45}ms`;
    observer.observe(node);
  });
} else {
  reveal.forEach((node) => node.classList.add("visible"));
}
