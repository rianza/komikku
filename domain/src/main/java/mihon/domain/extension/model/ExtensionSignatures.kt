package mihon.domain.extension.model

// KMK -->
/**
 * Well-known extension repository signing key fingerprints and related constants.
 *
 * Extracted from the removed legacy `CreateExtensionRepo` (extension-repo API), which was
 * superseded by the `ExtensionStore` APIs, so these values could keep being referenced.
 */
object ExtensionSignatures {
    const val REPO_HELP = "https://komikku-app.github.io/docs/guides/getting-started#adding-sources"

    // cuong-tran's key
    const val KOMIKKU_SIGNATURE = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
    const val REPO_SIGNATURE = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
}
// KMK <--
