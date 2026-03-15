# AzureVault Upload SDK - ProGuard/R8 Consumer Rules
# These rules are applied automatically when the SDK is consumed as a dependency.

# Keep public API classes
-keep class com.company.upload.AzureVaultUpload { *; }
-keep class com.company.upload.AzureVaultUploader { *; }
-keep class com.company.upload.UploadViewModel { *; }
-keep class com.company.upload.UploadUiState { *; }
-keep class com.company.upload.UploadUiState$* { *; }
-keep class com.company.upload.PlatformFile { *; }
-keep class com.company.upload.ImageFit { *; }
-keep class com.company.upload.ImageFormat { *; }

# Keep domain types (re-exported via upload-api)
-keep class com.company.upload.domain.UploadConfig { *; }
-keep class com.company.upload.domain.UploadState { *; }
-keep class com.company.upload.domain.UploadState$* { *; }
-keep class com.company.upload.domain.UploadError { *; }
-keep class com.company.upload.domain.UploadError$* { *; }
-keep class com.company.upload.domain.UploadMetadata { *; }
-keep class com.company.upload.domain.UploadProgress { *; }
-keep class com.company.upload.domain.UploadInfo { *; }
-keep class com.company.upload.domain.BatchUploadState { *; }
-keep class com.company.upload.domain.ChunkStrategy { *; }
-keep class com.company.upload.domain.ChunkStrategyType { *; }
-keep class com.company.upload.domain.RetryPolicy { *; }

# Keep extension functions
-keep class com.company.upload.PlatformFile_androidKt { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.company.upload.**$$serializer { *; }
-keepclassmembers class com.company.upload.** {
    *** Companion;
}
-keepclasseswithmembers class com.company.upload.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
