"use client";

import { useEffect } from "react";

export function LandingReveal() {
  useEffect(() => {
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const nodes = document.querySelectorAll<HTMLElement>(".lp-reveal");
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.remove("lp-awaiting");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.08 },
    );
    const reset = () => {
      observer.disconnect();
      nodes.forEach((node) => {
        node.classList.remove("lp-awaiting");
        if (
          !media.matches &&
          node.getBoundingClientRect().top > window.innerHeight
        ) {
          node.classList.add("lp-awaiting");
          observer.observe(node);
        }
      });
    };
    reset();
    media.addEventListener("change", reset);
    return () => {
      observer.disconnect();
      media.removeEventListener("change", reset);
      nodes.forEach((node) => node.classList.remove("lp-awaiting"));
    };
  }, []);
  return null;
}
