import { useQuery } from "@tanstack/react-query";
import { getOverview } from "../../lib/api/client";
import type { OverviewData } from "../../lib/api/types";

export function useOverview() {
  return useQuery<OverviewData>({
    queryKey: ["overview"],
    queryFn: getOverview,
  });
}
