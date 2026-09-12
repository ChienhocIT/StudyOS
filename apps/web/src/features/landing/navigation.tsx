"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowUpRight, BookOpen, ChevronDown, Menu, X } from "lucide-react";
import { features } from "./content";

export function LandingNavigation() {
  const [open, setOpen] = useState(false);
  const [mobile, setMobile] = useState(false);
  const header = useRef<HTMLElement>(null);
  const featureButton = useRef<HTMLButtonElement>(null);
  const mobileButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open && !mobile) return;
    const dismiss = (event: PointerEvent) => {
      if (!header.current?.contains(event.target as Node)) {
        setOpen(false);
        setMobile(false);
      }
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (open) {
        setOpen(false);
        featureButton.current?.focus();
      } else {
        setMobile(false);
        mobileButton.current?.focus();
      }
    };
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("keydown", escape);
    };
  }, [open, mobile]);

  function close() {
    setOpen(false);
    setMobile(false);
  }
  return (
    <header
      className="lp-header"
      ref={header}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) close();
      }}
    >
      <div className="lp-container lp-nav">
        <a
          className="lp-brand"
          href="/landing"
          aria-label="StudyOS, trang giới thiệu"
        >
          <span className="lp-brand-mark">
            <BookOpen size={21} strokeWidth={1.8} />
          </span>
          Study<span className="lp-brand-os">OS</span>
        </a>
        <button
          ref={mobileButton}
          className="lp-icon-button lp-mobile-toggle"
          aria-label={mobile ? "Đóng điều hướng" : "Mở điều hướng"}
          aria-expanded={mobile}
          aria-controls="landing-navigation"
          onClick={() => setMobile(!mobile)}
        >
          {mobile ? <X size={22} /> : <Menu size={22} />}
        </button>
        <nav
          id="landing-navigation"
          aria-label="Điều hướng chính"
          className={`lp-links ${mobile ? "is-open" : ""}`}
        >
          <button
            ref={featureButton}
            className="lp-nav-trigger"
            onClick={() => setOpen(!open)}
            aria-expanded={open}
            aria-controls="feature-menu"
          >
            Tính năng{" "}
            <ChevronDown size={15} className={open ? "is-rotated" : ""} />
          </button>
          <a href="#cach-bat-dau" onClick={close}>
            Cách bắt đầu
          </a>
          <a href="#cau-hoi" onClick={close}>
            Câu hỏi thường gặp
          </a>
          <a className="lp-button lp-button-small" href="/">
            Bắt đầu học <ArrowUpRight size={16} />
          </a>
        </nav>
      </div>
      {open && (
        <div id="feature-menu" className="lp-mega lp-container">
          <div className="lp-mega-intro">
            <BookOpen size={27} />
            <strong>
              Một nơi cho cả
              <br />
              hành trình học.
            </strong>
            <p>Từ tài liệu đầu tiên đến kiến thức bạn thực sự ghi nhớ.</p>
            <a href="#tinh-nang" onClick={close}>
              Khám phá tính năng <ArrowUpRight size={16} />
            </a>
          </div>
          <div className="lp-mega-links">
            {features.map(({ icon: Icon, title, description, href }) => (
              <a key={title} href={href} onClick={close}>
                <Icon size={21} strokeWidth={1.7} />
                <span>
                  <strong>{title}</strong>
                  <small>{description}</small>
                </span>
                <ArrowUpRight size={15} />
              </a>
            ))}
          </div>
        </div>
      )}
    </header>
  );
}
