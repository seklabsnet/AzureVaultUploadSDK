# Yeni App Ekleme Rehberi

AzureVault Upload SDK'yı yeni bir mobil uygulama için kullanmak istediğinde bu adımları takip et.

---

## 1. App'i Backend'e Kaydet

```bash
curl -X POST https://YOUR_FUNCTION_APP.azurewebsites.net/api/v1/admin/apps \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin-token>" \
  -d '{
    "appId": "yeni-app",
    "displayName": "Yeni App",
    "allowedMimeTypes": ["image/*", "video/*", "application/pdf"],
    "maxFileSize": 104857600,
    "storageQuota": 536870912000
  }'
```

### Parametreler

| Parametre | Zorunlu | Açıklama | Örnek |
|---|---|---|---|
| `appId` | ✅ | Küçük harf, tire ile. Unique. | `"my-cool-app"` |
| `displayName` | ✅ | İnsan-okunabilir isim | `"My Cool App"` |
| `allowedMimeTypes` | ✅ | Kabul edilen dosya tipleri | Aşağıya bak |
| `maxFileSize` | ✅ | Max dosya boyutu (byte) | Aşağıya bak |
| `storageQuota` | ✅ | Toplam depolama limiti (byte) | Aşağıya bak |
| `maxConcurrentUploads` | ❌ | Eş zamanlı upload (default: 3) | `5` |
| `webhookUrl` | ❌ | Upload bitince bildirim URL'i | `"https://api.myapp.com/hooks/upload"` |
| `rateLimitPerMinute` | ❌ | Rate limit (default: 1000) | `2000` |

### allowedMimeTypes Örnekleri

```json
["image/*"]                                    // Sadece görseller
["image/*", "application/pdf"]                 // Görseller + PDF
["image/*", "video/*", "audio/*"]              // Medya dosyaları
["image/*", "video/*", "application/pdf"]      // Görseller + Video + PDF
["*/*"]                                        // Her şey (dikkatli kullan)
```

### maxFileSize Referans

```
10 MB   =     10485760
50 MB   =     52428800
100 MB  =    104857600
500 MB  =    524288000
1 GB    =   1073741824
5 GB    =   5368709120
```

### storageQuota Referans

```
100 GB  =   107374182400
500 GB  =   536870912000
1 TB    =  1099511627776
5 TB    =  5497558138880
```

### Response

```json
{
  "success": true,
  "data": {
    "appId": "yeni-app",
    "displayName": "Yeni App",
    "clientSecret": "cvlt_yeni-app_a1b2c3d4e5f6...",
    "message": "App registered. Store the clientSecret securely — it won't be shown again."
  }
}
```

> ⚠️ `clientSecret` sadece BİR KEZ gösterilir. Hemen güvenli bir yere kaydet.

---

## 2. Storage Container Oluştur

```bash
az storage container create \
  --name uploads-yeni-app \
  --account-name YOUR_STORAGE_ACCOUNT \
  --auth-mode login
```

---

## 3. SDK'yı App'e Entegre Et

### Android (Application.onCreate)

```kotlin
AzureVaultUpload.initialize(
    context = this,
    config = UploadConfig(
        baseUrl = "https://YOUR_FUNCTION_APP.azurewebsites.net/api",
        appId = "yeni-app",
        authProvider = { tokenStore.getAccessToken() }
    )
)
```

### iOS (AppDelegate)

```swift
AzureVaultUpload.shared.initialize(
    config: UploadConfig(
        baseUrl: "https://YOUR_FUNCTION_APP.azurewebsites.net/api",
        appId: "yeni-app",
        authProvider: { TokenStore.shared.getAccessToken() }
    )
)
```

---

## 4. Test Et

```kotlin
val file = uri.toPlatformFile(contentResolver)
AzureVaultUpload.uploader().upload(
    file = file,
    metadata = UploadMetadata(entityType = "document", entityId = "doc_001")
).collect { state ->
    println("State: $state")
}
```

---

## Mevcut App'leri Görüntüle

```bash
curl https://YOUR_FUNCTION_APP.azurewebsites.net/api/v1/admin/apps \
  -H "Authorization: Bearer <admin-token>"
```

## App Config Güncelle

```bash
curl -X PUT https://YOUR_FUNCTION_APP.azurewebsites.net/api/v1/admin/apps/yeni-app \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin-token>" \
  -d '{
    "maxFileSize": 524288000,
    "allowedMimeTypes": ["image/*", "video/*", "audio/*", "application/pdf"]
  }'
```

---

## Checklist

- [ ] `POST /v1/admin/apps` ile app kaydet
- [ ] `clientSecret`'ı güvenli bir yere kaydet
- [ ] `az storage container create --name uploads-{appId}` çalıştır
- [ ] SDK'yı app'e entegre et (5 satır init + 1 satır file ref)
- [ ] Test upload yap
