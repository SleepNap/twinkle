import { Outlet } from "react-router-dom";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { TooltipProvider } from "@/components/ui/tooltip";

export function Shell() {
  return (
    <TooltipProvider delayDuration={200}>
      <div className="flex min-h-[100dvh] bg-background text-foreground">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <Topbar />
          <main className="flex-1 p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </TooltipProvider>
  );
}
