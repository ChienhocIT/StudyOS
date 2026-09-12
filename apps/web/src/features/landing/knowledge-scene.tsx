"use client";

import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import { Pause, Play } from "lucide-react";

export function KnowledgeScene() {
  const host = useRef<HTMLDivElement>(null);
  const pause = useRef(false);
  const sync = useRef<() => void>(() => {});
  const [paused, setPaused] = useState(false);
  const [requested, setRequested] = useState(false);
  const [status, setStatus] = useState<
    "idle" | "loading" | "ready" | "fallback"
  >("idle");

  useEffect(() => {
    // The poster is the LCP-friendly default. Three.js is downloaded only after
    // the visitor explicitly asks for movement; reduced-motion remains static.
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    if (!requested) return;
    let disposed = false;
    let cleanup = () => {};
    const target = host.current!;
    async function init() {
      try {
        setStatus("loading");
        const THREE = await import("three");
        const { RoundedBoxGeometry } =
          await import("three/addons/geometries/RoundedBoxGeometry.js");
        if (disposed) return;
        const renderer = new THREE.WebGLRenderer({
          alpha: true,
          antialias: true,
          powerPreference: "low-power",
        });
        cleanup = () => {
          renderer.dispose();
          renderer.domElement.remove();
        };
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
        renderer.setClearColor(0x000000, 0);
        target.appendChild(renderer.domElement);
        const scene = new THREE.Scene();
        const camera = new THREE.PerspectiveCamera(34, 1, 0.1, 60);
        camera.position.set(0, 1.2, 10.5);
        camera.lookAt(0, 0, 0);
        scene.add(new THREE.HemisphereLight(0xf1fff8, 0x46665a, 3));
        const key = new THREE.DirectionalLight(0xffffff, 4);
        key.position.set(-3, 5, 4);
        scene.add(key);
        const rim = new THREE.DirectionalLight(0x8ee0bd, 2);
        rim.position.set(4, 0, -2);
        scene.add(rim);
        const group = new THREE.Group();
        scene.add(group);
        group.rotation.set(0.46, -0.52, -0.18);
        const green = new THREE.MeshStandardMaterial({
          color: 0x176b57,
          roughness: 0.32,
          metalness: 0.22,
        });
        const paper = new THREE.MeshStandardMaterial({
          color: 0xe9f1eb,
          roughness: 0.75,
        });
        const mint = new THREE.MeshStandardMaterial({
          color: 0x9edbc1,
          metalness: 0.28,
          roughness: 0.3,
        });
        const sheetGeometry = new RoundedBoxGeometry(2.65, 0.12, 3.25, 3, 0.07);
        for (let i = 0; i < 5; i++) {
          const sheet = new THREE.Mesh(
            sheetGeometry,
            i === 0 || i === 4 ? green : paper,
          );
          sheet.position.y = (i - 2) * 0.34;
          sheet.rotation.y = (i - 2) * 0.045;
          group.add(sheet);
        }
        // Raised lines suggest notes, without fabricating a product screenshot.
        const lineGeometry = new RoundedBoxGeometry(1.6, 0.025, 0.065, 2, 0.02);
        for (let i = 0; i < 4; i++) {
          const line = new THREE.Mesh(lineGeometry, mint);
          line.position.set(-0.12, 0.75, -0.7 + i * 0.37);
          line.scale.x = i === 3 ? 0.55 : 1;
          group.add(line);
        }
        const orbit = new THREE.Group();
        scene.add(orbit);
        orbit.rotation.set(1.13, -0.22, -0.25);
        const ringMaterial = new THREE.MeshStandardMaterial({
          color: 0x71b59b,
          roughness: 0.45,
          transparent: true,
          opacity: 0.55,
        });
        const ringGeometry = new THREE.TorusGeometry(2.65, 0.009, 6, 100);
        orbit.add(new THREE.Mesh(ringGeometry, ringMaterial));
        const sphereGeometry = new THREE.SphereGeometry(0.13, 20, 14);
        const beads: import("three").Mesh[] = [];
        for (let i = 0; i < 4; i++) {
          const bead = new THREE.Mesh(sphereGeometry, i % 2 ? mint : green);
          orbit.add(bead);
          beads.push(bead);
        }
        const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
        let visible = true;
        let phase = 0;
        let previous = 0;
        let pointerX = 0;
        let pointerY = 0;
        const draw = (time: number) => {
          const moving = !reduced.matches && !pause.current;
          if (moving && previous)
            phase += Math.min(time - previous, 40) * 0.00025;
          previous = time;
          group.position.y = moving ? Math.sin(phase * 2) * 0.09 : 0;
          group.rotation.y +=
            (-0.52 + (moving ? pointerX * 0.18 : 0) - group.rotation.y) * 0.04;
          group.rotation.x +=
            (0.46 + (moving ? pointerY * 0.1 : 0) - group.rotation.x) * 0.04;
          beads.forEach((bead, i) => {
            const angle = phase + (i * Math.PI) / 2;
            bead.position.set(
              Math.cos(angle) * 2.65,
              Math.sin(angle) * 2.65,
              0,
            );
          });
          renderer.render(scene, camera);
        };
        const update = () => {
          previous = 0;
          renderer.setAnimationLoop(null);
          if (visible && !document.hidden) {
            draw(0);
            if (!reduced.matches && !pause.current)
              renderer.setAnimationLoop(draw);
          }
        };
        sync.current = update;
        const resize = new ResizeObserver(() => {
          const { width, height } = target.getBoundingClientRect();
          if (!width || !height) return;
          renderer.setSize(width, height, false);
          camera.aspect = width / height;
          camera.updateProjectionMatrix();
          draw(0);
        });
        resize.observe(target);
        const observer = new IntersectionObserver(([entry]) => {
          visible = entry.isIntersecting;
          update();
        });
        observer.observe(target);
        const move = (event: PointerEvent) => {
          const rect = target.getBoundingClientRect();
          pointerX = (event.clientX - rect.left) / rect.width - 0.5;
          pointerY = (event.clientY - rect.top) / rect.height - 0.5;
        };
        const leave = () => {
          pointerX = 0;
          pointerY = 0;
        };
        const lost = (event: Event) => {
          event.preventDefault();
          renderer.setAnimationLoop(null);
          setStatus("fallback");
        };
        target.addEventListener("pointermove", move);
        target.addEventListener("pointerleave", leave);
        renderer.domElement.addEventListener("webglcontextlost", lost);
        document.addEventListener("visibilitychange", update);
        reduced.addEventListener("change", update);
        cleanup = () => {
          sync.current = () => {};
          renderer.setAnimationLoop(null);
          resize.disconnect();
          observer.disconnect();
          target.removeEventListener("pointermove", move);
          target.removeEventListener("pointerleave", leave);
          renderer.domElement.removeEventListener("webglcontextlost", lost);
          document.removeEventListener("visibilitychange", update);
          reduced.removeEventListener("change", update);
          [sheetGeometry, lineGeometry, ringGeometry, sphereGeometry].forEach(
            (geometry) => geometry.dispose(),
          );
          [green, paper, mint, ringMaterial].forEach((material) =>
            material.dispose(),
          );
          renderer.dispose();
          renderer.domElement.remove();
        };
        setStatus("ready");
        update();
      } catch {
        if (!disposed) {
          cleanup();
          setStatus("fallback");
        }
      }
    }
    const timer = window.setTimeout(init, 250);
    return () => {
      disposed = true;
      clearTimeout(timer);
      cleanup();
    };
  }, [requested]);

  return (
    <div className="lp-scene-frame" data-state={status}>
      <div
        className="lp-scene"
        ref={host}
        role={status === "ready" ? "img" : undefined}
        aria-label={
          status === "ready"
            ? "Mô hình 3D các trang kiến thức kết nối trong một quỹ đạo học tập"
            : undefined
        }
        aria-hidden={status !== "ready" ? true : undefined}
      />
      {status !== "ready" && (
        <Image
          className="lp-scene-poster"
          src="/images/knowledge-poster.webp"
          alt="Các lớp trang kiến thức xanh ngọc kết nối trong một quỹ đạo"
          fill
          sizes="(max-width: 767px) 100vw, 50vw"
          preload
          fetchPriority="high"
          unoptimized
        />
      )}
      {status === "idle" && (
        <button className="lp-scene-control" onClick={() => setRequested(true)}>
          <Play size={15} />
          <span>Bật chuyển động 3D</span>
        </button>
      )}
      {status === "loading" && (
        <span className="lp-scene-control" role="status">
          Đang tải chuyển động 3D…
        </span>
      )}
      {status === "ready" && (
        <button
          className="lp-scene-control"
          aria-label={
            paused ? "Tiếp tục chuyển động 3D" : "Tạm dừng chuyển động 3D"
          }
          aria-pressed={paused}
          onClick={() => {
            pause.current = !paused;
            setPaused(!paused);
            sync.current();
          }}
        >
          {paused ? <Play size={15} /> : <Pause size={15} />}
          <span>Chuyển động 3D</span>
        </button>
      )}
    </div>
  );
}
