"use client";

import { authService } from "@/services/authService";
import { useQuery } from "@tanstack/react-query";
import { usePathname, useRouter } from "next/navigation";
import { ReactNode, useEffect, useState } from "react";
import { TOKEN_KEY } from "@/lib/api";
import { LoadingState } from "@/src/components/shared/AsyncState";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { navigationItems } from "./navigation";

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const { data: user, isError, isLoading } = useQuery({
    queryKey: ["me"],
    queryFn: authService.me,
    retry: false
  });

  const userRole = user?.role;
  const visibleItems = navigationItems.filter((item) => userRole ? item.roles.includes(userRole) : false);

  useEffect(() => {
    if (typeof window !== "undefined" && !window.localStorage.getItem(TOKEN_KEY)) {
      router.replace("/login");
    }
  }, [router]);

  useEffect(() => {
    if (isError) {
      authService.logout();
    }
  }, [isError]);

  if (isLoading) {
    return (
      <main className="flex min-h-screen items-center justify-center p-4">
        <div className="w-full max-w-sm">
          <LoadingState label="Carregando sessão..." />
        </div>
      </main>
    );
  }

  return (
    <div className="min-h-screen">
      <Sidebar
        items={visibleItems}
        pathname={pathname}
        role={user?.role}
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        collapsed={collapsed}
        onToggleCollapsed={() => setCollapsed((value) => !value)}
      />
      <div className={collapsed ? "transition-all duration-300 lg:pl-24" : "transition-all duration-300 lg:pl-72"}>
        <Topbar user={user} onMenu={() => setSidebarOpen(true)} onLogout={authService.logout} />
        <main className="mx-auto w-full max-w-[1500px] px-4 py-6 sm:px-6 lg:px-8">{children}</main>
      </div>
    </div>
  );
}
