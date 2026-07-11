import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Triết học AI",
  description: "Trợ lý học tập triết học dựa trên giáo trình Mác - Lênin",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="vi" suppressHydrationWarning>
      <body>{children}</body>
    </html>
  );
}
