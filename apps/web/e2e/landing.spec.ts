import { expect, test } from "@playwright/test";

test("landing renders SEO content and functional desktop navigation", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/landing");
  await expect(page).toHaveTitle(/StudyOS.*Học từ tài liệu/);
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.locator('meta[name="description"]')).toHaveAttribute(
    "content",
    /flashcard/,
  );
  const data = JSON.parse(
    (await page.locator('script[type="application/ld+json"]').textContent()) ||
      "{}",
  );
  expect(data["@type"]).toBe("SoftwareApplication");
  const trigger = page.getByRole("button", { name: "Tính năng", exact: true });
  await trigger.click();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(page.locator("#feature-menu a")).toHaveCount(7);
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
  await trigger.click();
  await page
    .locator("#feature-menu")
    .getByRole("link", { name: /Quiz & flashcard/ })
    .click();
  await expect(page).toHaveURL(/#flashcard$/);
  await expect(page.locator("#feature-menu")).toHaveCount(0);
  expect(errors).toEqual([]);
});

test("flashcard demo completes and resets; FAQ works with keyboard", async ({
  page,
}) => {
  await page.goto("/landing");
  for (let index = 0; index < 3; index++) {
    await page.getByRole("button", { name: "Lật thẻ", exact: true }).click();
    await expect(page.locator(".lp-answer")).toBeVisible();
    await page.getByRole("button", { name: "Thẻ tiếp theo" }).click();
  }
  await expect(
    page.getByRole("heading", { name: "Thêm một điều đã nhớ." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Thử lại" }).click();
  await expect(
    page.getByRole("heading", { name: "Active recall là gì?" }),
  ).toBeVisible();
  const question = page.locator("summary").first();
  await question.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("details").first()).toHaveAttribute("open", "");
  await page.keyboard.press("Enter");
  await expect(page.locator("details").first()).not.toHaveAttribute("open");
});

test("3D scene renders and can be paused", async ({ page }) => {
  await page.goto("/landing");
  await expect(page.locator(".lp-scene-frame")).toHaveAttribute(
    "data-state",
    "idle",
  );
  await page.getByRole("button", { name: "Bật chuyển động 3D" }).click();
  await expect(page.locator(".lp-scene-frame")).toHaveAttribute(
    "data-state",
    "ready",
  );
  await expect(page.locator("canvas")).toBeVisible();
  await page.getByRole("button", { name: "Tạm dừng chuyển động 3D" }).click();
  await expect(
    page.getByRole("button", { name: "Tiếp tục chuyển động 3D" }),
  ).toHaveAttribute("aria-pressed", "true");
});

test("mobile loads Three.js only after the visitor enables it", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/landing");
  await expect(page.locator(".lp-scene-frame")).toHaveAttribute(
    "data-state",
    "idle",
  );
  await expect(page.locator("canvas")).toHaveCount(0);
  await page.getByRole("button", { name: "Bật chuyển động 3D" }).click();
  await expect(page.locator(".lp-scene-frame")).toHaveAttribute(
    "data-state",
    "ready",
  );
  await expect(page.locator("canvas")).toBeVisible();
});

test("mobile menu, dark mode and reduced motion remain usable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.goto("/landing");
  await page.getByRole("button", { name: "Mở điều hướng" }).click();
  await page.getByRole("button", { name: "Tính năng", exact: true }).click();
  await page
    .locator("#feature-menu")
    .getByRole("link", { name: /Tài liệu & ghi chú/ })
    .click();
  await expect(page).toHaveURL(/#tai-lieu$/);
  await expect(
    page.getByRole("button", { name: "Mở điều hướng" }),
  ).toHaveAttribute("aria-expanded", "false");
  for (const width of [320, 390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  }
  await expect(page.locator(".lp-scene-frame")).toHaveAttribute(
    "data-state",
    "idle",
  );
  await expect(page.locator(".lp-scene-control")).toBeHidden();
  expect(
    await page
      .locator(".lp-page")
      .evaluate((el) => getComputedStyle(el).backgroundColor),
  ).toBe("rgb(16, 27, 22)");
});

test("essential landing content is available without JavaScript", async ({
  browser,
  baseURL,
}) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto(`${baseURL}/landing`);
  await expect(page.locator("h1")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Bạn có thể đang thắc mắc." }),
  ).toBeVisible();
  await expect(page.locator(".lp-hero a").first()).toHaveAttribute("href", "/");
  await context.close();
});
