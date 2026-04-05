#!/usr/bin/env node

/**
 * vault-cli — Admin CLI for AzureVaultUploadSDK
 *
 * Setup:
 *   export VAULT_ADMIN_KEY="your-admin-key"
 *   export VAULT_API_URL="https://your-app.azurewebsites.net/api"
 *
 * Usage:
 *   vault-cli register <appId> <displayName> [options]
 *   vault-cli list
 *   vault-cli rotate <appId>
 *   vault-cli status
 */

const VAULT_API_URL = process.env.VAULT_API_URL;
const VAULT_ADMIN_KEY = process.env.VAULT_ADMIN_KEY;

// ─── Helpers ──────────────────────────────────────────────

function die(msg) {
  console.error(`\n  ✗ ${msg}\n`);
  process.exit(1);
}

function ok(msg) {
  console.log(`  ✓ ${msg}`);
}

function heading(msg) {
  console.log(`\n  ${msg}`);
  console.log("  " + "─".repeat(50));
}

function kv(key, value) {
  console.log(`  ${key.padEnd(22)} ${value}`);
}

function requireEnv() {
  if (!VAULT_API_URL) die("VAULT_API_URL not set. Export it first:\n    export VAULT_API_URL=\"https://your-app.azurewebsites.net/api\"");
  if (!VAULT_ADMIN_KEY) die("VAULT_ADMIN_KEY not set. Export it first:\n    export VAULT_ADMIN_KEY=\"your-secret-key\"");
}

async function api(method, path, body) {
  const url = `${VAULT_API_URL}${path}`;
  const opts = {
    method,
    headers: {
      "x-admin-key": VAULT_ADMIN_KEY,
      "content-type": "application/json",
    },
  };
  if (body) opts.body = JSON.stringify(body);

  const res = await fetch(url, opts);
  const data = await res.json().catch(() => null);

  if (!res.ok) {
    const msg = data?.error?.message || data?.message || `HTTP ${res.status}`;
    die(`API error: ${msg}`);
  }
  return data?.data ?? data;
}

function formatBytes(bytes) {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(0)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(0)} MB`;
  return `${bytes} bytes`;
}

// ─── Commands ─────────────────────────────────────────────

async function cmdRegister(args) {
  const appId = args[0];
  const displayName = args[1];

  if (!appId) die("Usage: vault-cli register <appId> <displayName> [--mime 'image/*,video/*'] [--max-size 100MB] [--quota 500GB]");
  if (!displayName) die("displayName is required. Example: vault-cli register centauri \"Centauri App\"");

  // Parse optional flags
  const mimeTypes = getFlag(args, "--mime")?.split(",") || ["image/*", "video/*", "application/pdf"];
  const maxSizeStr = getFlag(args, "--max-size") || "100MB";
  const quotaStr = getFlag(args, "--quota") || "500GB";

  const maxFileSize = parseSize(maxSizeStr);
  const storageQuota = parseSize(quotaStr);

  heading("Registering app...");
  kv("App ID:", appId);
  kv("Display Name:", displayName);
  kv("Allowed MIME:", mimeTypes.join(", "));
  kv("Max File Size:", formatBytes(maxFileSize));
  kv("Storage Quota:", formatBytes(storageQuota));
  console.log();

  const result = await api("POST", "/v1/admin/apps", {
    appId,
    displayName,
    allowedMimeTypes: mimeTypes,
    maxFileSize,
    storageQuota,
  });

  heading("App registered successfully!");
  console.log();
  kv("App ID:", result.appId);
  kv("Display Name:", result.displayName);
  console.log();

  // Box the secret so it's impossible to miss
  const secret = result.clientSecret;
  console.log("  ┌─────────────────────────────────────────────────────────────┐");
  console.log("  │  CLIENT SECRET (save this — shown ONCE):                    │");
  console.log(`  │  ${secret.padEnd(58)}│`);
  console.log("  └─────────────────────────────────────────────────────────────┘");
  console.log();

  heading("Next steps for centauri-backend .env:");
  console.log();
  console.log(`  VAULT_APP_ID=${result.appId}`);
  console.log(`  VAULT_CLIENT_SECRET=${secret}`);
  console.log(`  VAULT_BASE_URL=${VAULT_API_URL}`);
  console.log(`  UPLOAD_GRANT_SECRET=<generate a random 32+ char string>`);
  console.log();
  ok("Done. Keep that secret safe!\n");
}

async function cmdList() {
  heading("Registered apps");
  console.log();

  const apps = await api("GET", "/v1/admin/apps");

  if (!apps || apps.length === 0) {
    console.log("  (no apps registered yet)\n");
    return;
  }

  for (const app of apps) {
    kv("App ID:", app.appId);
    kv("Display Name:", app.displayName);
    kv("MIME Types:", app.allowedMimeTypes.join(", "));
    kv("Max File Size:", formatBytes(app.maxFileSize));
    kv("Storage Quota:", formatBytes(app.storageQuota));
    kv("Rate Limit:", `${app.rateLimitPerMinute}/min`);
    kv("Active:", app.isActive ? "yes" : "no");
    kv("Created:", app.createdAt);
    console.log();
  }
}

async function cmdRotate(args) {
  const appId = args[0];
  if (!appId) die("Usage: vault-cli rotate <appId>");

  heading(`Rotating secret for "${appId}"...`);
  console.log();

  const result = await api("POST", `/v1/admin/apps/${appId}/rotate-secret`);

  const secret = result.clientSecret;
  console.log("  ┌─────────────────────────────────────────────────────────────┐");
  console.log("  │  NEW CLIENT SECRET (save this — shown ONCE):                │");
  console.log(`  │  ${secret.padEnd(58)}│`);
  console.log("  └─────────────────────────────────────────────────────────────┘");
  console.log();
  ok("Secret rotated. Update your .env files!\n");
}

async function cmdStatus() {
  heading("Vault Backend Status");
  console.log();
  kv("API URL:", VAULT_API_URL || "(not set)");
  kv("Admin Key:", VAULT_ADMIN_KEY ? "configured" : "(not set)");

  if (!VAULT_API_URL || !VAULT_ADMIN_KEY) {
    console.log();
    die("Missing env vars. Run:\n    export VAULT_API_URL=\"https://your-app.azurewebsites.net/api\"\n    export VAULT_ADMIN_KEY=\"your-secret-key\"");
  }

  // Try to reach the API
  try {
    const apps = await api("GET", "/v1/admin/apps");
    kv("Connection:", "OK");
    kv("Registered apps:", `${apps.length}`);
    console.log();
    ok("Everything looks good!\n");
  } catch {
    kv("Connection:", "FAILED");
    console.log();
    die("Could not reach the API. Check VAULT_API_URL.\n");
  }
}

// ─── Flag parsing ─────────────────────────────────────────

function getFlag(args, flag) {
  const idx = args.indexOf(flag);
  if (idx === -1 || idx + 1 >= args.length) return null;
  return args[idx + 1];
}

function parseSize(str) {
  const match = str.toUpperCase().match(/^(\d+(?:\.\d+)?)\s*(B|KB|MB|GB|TB)$/);
  if (!match) die(`Invalid size: "${str}". Use format like 100MB, 5GB, 1TB`);
  const num = parseFloat(match[1]);
  const unit = match[2];
  const multipliers = { B: 1, KB: 1024, MB: 1_048_576, GB: 1_073_741_824, TB: 1_099_511_627_776 };
  return Math.floor(num * multipliers[unit]);
}

// ─── Main ─────────────────────────────────────────────────

function printHelp() {
  console.log(`
  vault-cli — Admin CLI for AzureVaultUploadSDK

  Setup:
    export VAULT_API_URL="https://your-app.azurewebsites.net/api"
    export VAULT_ADMIN_KEY="your-secret-key"

  Commands:
    register <appId> <displayName> [options]   Register a new app
      --mime "image/*,video/*"                  Allowed MIME types (default: image/*,video/*,application/pdf)
      --max-size 100MB                         Max upload size (default: 100MB)
      --quota 500GB                            Storage quota (default: 500GB)

    list                                       List all registered apps
    rotate <appId>                             Rotate client secret
    status                                     Check connection & config

  Examples:
    vault-cli register centauri "Centauri App"
    vault-cli register centauri "Centauri App" --mime "image/*,video/*" --max-size 500MB --quota 1TB
    vault-cli list
    vault-cli rotate centauri
    vault-cli status
`);
}

async function main() {
  const args = process.argv.slice(2);
  const cmd = args[0];

  if (!cmd || cmd === "--help" || cmd === "-h" || cmd === "help") {
    printHelp();
    process.exit(0);
  }

  if (cmd !== "status") requireEnv();

  switch (cmd) {
    case "register":
      await cmdRegister(args.slice(1));
      break;
    case "list":
      await cmdList();
      break;
    case "rotate":
      await cmdRotate(args.slice(1));
      break;
    case "status":
      await cmdStatus();
      break;
    default:
      die(`Unknown command: "${cmd}". Run vault-cli --help for usage.`);
  }
}

main().catch((err) => {
  die(err.message || String(err));
});
