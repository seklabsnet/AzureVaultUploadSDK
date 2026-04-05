# vault-cli

Admin CLI for AzureVaultUploadSDK. Register apps, rotate secrets, manage config.

Zero dependencies. Just Node.js 18+.

## Setup (One Time)

### 1. Set an admin key on your Azure Function App

```bash
# Pick a strong random key
ADMIN_KEY=$(openssl rand -hex 32)
echo "Your admin key: $ADMIN_KEY"

# Set it on Azure
az functionapp config appsettings set \
  --name YOUR_FUNCTION_APP \
  --resource-group YOUR_RG \
  --settings ADMIN_API_KEY=$ADMIN_KEY
```

### 2. Export env vars locally

Add these to your `~/.zshrc` or `~/.bashrc`:

```bash
export VAULT_API_URL="https://YOUR_FUNCTION_APP.azurewebsites.net/api"
export VAULT_ADMIN_KEY="your-admin-key-from-step-1"
```

### 3. Verify

```bash
node vault-cli.mjs status
```

## Usage

```bash
# Register a new app
node vault-cli.mjs register centauri "Centauri App"

# Register with custom limits
node vault-cli.mjs register centauri "Centauri App" --mime "image/*,video/*" --max-size 500MB --quota 1TB

# List all apps
node vault-cli.mjs list

# Rotate a secret (generates new clientSecret)
node vault-cli.mjs rotate centauri

# Check connection
node vault-cli.mjs status
```

## Full Flow: First-Time Setup

```bash
# 1. Generate admin key and set on Azure
ADMIN_KEY=$(openssl rand -hex 32)
az functionapp config appsettings set --name myapp --resource-group myrg --settings ADMIN_API_KEY=$ADMIN_KEY

# 2. Export locally
export VAULT_API_URL="https://myapp.azurewebsites.net/api"
export VAULT_ADMIN_KEY="$ADMIN_KEY"

# 3. Register your app
node vault-cli.mjs register centauri "Centauri App"
# => Prints clientSecret (SAVE IT!)

# 4. Generate a grant secret
GRANT_SECRET=$(openssl rand -hex 32)
echo "Grant secret: $GRANT_SECRET"

# 5. Set on centauri-backend (Container Apps / .env)
# VAULT_APP_ID=centauri
# VAULT_CLIENT_SECRET=cvlt_centauri_xxxxx   (from step 3)
# VAULT_BASE_URL=https://myapp.azurewebsites.net/api
# UPLOAD_GRANT_SECRET=xxxxx                  (from step 4)
```

## Security

- The CLI itself is safe to be public — it's just an HTTP client
- All admin endpoints require `X-Admin-Key` header
- Without the key, you get 401/403
- `ADMIN_API_KEY` lives only in Azure Function App settings
- `clientSecret` is shown once on register/rotate — store it securely
