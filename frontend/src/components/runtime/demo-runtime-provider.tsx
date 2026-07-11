"use client";

import { AssistantRuntimeProvider } from "@assistant-ui/react";
import { useDataStreamRuntime } from "@assistant-ui/react-data-stream";

export function DemoRuntimeProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const runtime = useDataStreamRuntime({
    api: "/api/chat/ui-stream",
    protocol: "ui-message-stream",
  });

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      {children}
    </AssistantRuntimeProvider>
  );
}
