import { SecretClient } from "@azure/keyvault-secrets";
import { DefaultAzureCredential } from "@azure/identity";

let secretClient: SecretClient;
const secretCache = new Map<string, string>();

function getSecretClient(): SecretClient {
  if (!secretClient) {
    const vaultUrl = process.env.KeyVaultUrl;
    if (!vaultUrl) {
      throw new Error("KeyVaultUrl environment variable is not set");
    }
    const credential = new DefaultAzureCredential();
    secretClient = new SecretClient(vaultUrl, credential);
  }
  return secretClient;
}

export async function getSecret(secretName: string): Promise<string> {
  const cached = secretCache.get(secretName);
  if (cached !== undefined) {
    return cached;
  }

  const client = getSecretClient();
  const secret = await client.getSecret(secretName);

  if (!secret.value) {
    throw new Error(`Secret "${secretName}" has no value`);
  }

  secretCache.set(secretName, secret.value);
  return secret.value;
}
