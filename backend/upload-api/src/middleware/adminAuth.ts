import { HttpRequest } from "@azure/functions";
import { authenticateRequest, AuthContext } from "./auth.js";
import { ForbiddenError } from "../shared/errors.js";

const ADMIN_APP_IDS = (process.env.ADMIN_APP_IDS || "").split(",").filter(Boolean);

export async function authenticateAdmin(request: HttpRequest): Promise<AuthContext> {
  const auth = await authenticateRequest(request);

  // Check if appId is in admin allowlist
  if (ADMIN_APP_IDS.length > 0 && !ADMIN_APP_IDS.includes(auth.appId)) {
    throw new ForbiddenError("Admin access required. Contact system administrator.");
  }

  return auth;
}
