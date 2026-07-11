import { Claude } from "@/components/examples/claude";
import { DemoRuntimeProvider } from "@/components/runtime/demo-runtime-provider";

export default function Page() {
  return (
    <main className="h-dvh overflow-hidden">
      <DemoRuntimeProvider>
        <Claude />
      </DemoRuntimeProvider>
    </main>
  );
}
