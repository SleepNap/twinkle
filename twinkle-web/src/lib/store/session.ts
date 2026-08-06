import { create } from "zustand";

interface SessionState {
  user: string | null;
  setUser: (user: string | null) => void;
}

export const useSession = create<SessionState>((set) => ({
  user: "admin",
  setUser: (user) => set({ user }),
}));
