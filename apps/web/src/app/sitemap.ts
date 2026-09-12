import type { MetadataRoute } from "next";
import { siteUrl } from "@/features/landing/seo";

export default function sitemap(): MetadataRoute.Sitemap {
  return siteUrl
    ? [{ url: `${siteUrl}/landing`, changeFrequency: "monthly", priority: 1 }]
    : [];
}
