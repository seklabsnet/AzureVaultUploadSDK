import { HttpRequest } from "@azure/functions";
import { authenticateRequest, AuthContext } from "./auth.js";
import { AuthError, ForbiddenError } from "../shared/errors.js";

const ADMIN_API_KEY = process.env.ADMIN_API_KEY || "";
const ADMIN_APP_IDS = (process.env.ADMIN_APP_IDS || "").split(",").filter(Boolean);

export async function authenticateAdmin(request: HttpRequest): Promise<AuthContext> {
  // Method 1: X-Admin-Key header (for CLI / first-time setup)
  const adminKey = request.headers.get("x-admin-key");
  if (adminKey) {
    if (!ADMIN_API_KEY) {
      throw new AuthError("ADMIN_API_KEY not configured on server");
    }
    if (adminKey !== ADMIN_API_KEY) {
      throw new ForbiddenError("Invalid admin key");
    }
    return { appId: "_admin" };
  }

  // Method 2: Bearer token from registered admin app (existing flow)
  const auth = await authenticateRequest(request);

  if (ADMIN_APP_IDS.length > 0 && !ADMIN_APP_IDS.includes(auth.appId)) {
    throw new ForbiddenError("Admin access required. Contact system administrator.");
  }

  return auth;
}
