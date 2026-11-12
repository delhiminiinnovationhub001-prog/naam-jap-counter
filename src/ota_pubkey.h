#pragma once

// Public key that verifies firmware downloaded from GitHub (RSA-2048, SHA-256).
// The matching private key is tools/keys/ota_signing_key.pem on the publishing
// PC only (never committed). Only firmware signed with it can be installed
// from GitHub. Generated with openssl; replace both together.

static const char OTA_PUBLIC_KEY[] =
    "-----BEGIN PUBLIC KEY-----\n"
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr/fAIb8ciBO9H6xXjA3J\n"
    "FzTysmAxG6QtKIHRp8OIaTtdi6a6+3VbeQkzbPSpYedunok317VmOWqw8JwX0I+y\n"
    "4YU/XC85LGLWUl5TD4GNZ6RbKDdWDbg/+vhHmuIh9FyMcSfr5+DSpkrwanpCwQ4T\n"
    "B98h/XzEoaDckf5tqgapDh4qQNk9gKwnxnSfVCoGtlUs8iOUiGe2QhaWAOz0+Zq7\n"
    "eSgavYpmYCbQu+LNj7qK4KtZNpP5O11WRp5Hhr17CzP5PE2OR+djF+EGs0KbbqJd\n"
    "wj9MYtwPSyY1acArCe/0Q1IxVAGNNXkF3E3xzjDLMGxWugS6pQJJ+2t7+rEr564p\n"
    "bwIDAQAB\n"
    "-----END PUBLIC KEY-----\n";
