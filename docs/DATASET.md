# Merchant dataset

Kosh categorises imported transactions using an open, community-maintained dataset:
[`core/sms/src/main/resources/kosh/merchants.json`](../core/sms/src/main/resources/kosh/merchants.json).

## Format

```json
{
  "schemaVersion": 1,
  "description": "...",
  "rules": [
    {
      "category": "groceries",
      "brands": ["blinkit", "zepto", "swiggy ?instamart", "big ?basket"],
      "generic": ["kirana", "supermarket"]
    }
  ]
}
```

- **`rules` are checked in order; the first match wins.** That's why more specific rules come first
  (for example `groceries` with "swiggy instamart" comes before `food` with "swiggy").
- **`brands`**: patterns for specific merchants. They're matched against the merchant name, then the SMS text.
- **`generic`**: business words ("pharmacy", "restaurant"), matched against the merchant name only.
- Patterns are **case-insensitive regular expressions**. Useful pieces:
  - `\\b` marks a word boundary, written as `\\b` in JSON: `"\\bkfc\\b"`.
  - ` ?` means an optional space: `"big ?basket"` matches "BigBasket" and "Big Basket".
- **`category`** must be one of: `food`, `groceries`, `shopping`, `transport`, `fuel`, `bills`, `rent`,
  `entertainment`, `health`, `fitness`, `education`, `travel`, `subscriptions`, `emi`, `investment`,
  `insurance`, `taxes`, `cash`, `people`, `personal_care`, `household`, `gifts`, `transfer`.

## Contributing a merchant

1. Find the right category's rule and add the brand to `brands`. Keep patterns specific; a too-broad pattern
   (like `"pay"`) will mis-categorise many payments.
2. If a brand name contains another brand (`"amazon prime"` vs `"amazon"`), make sure the more specific one
   is in a rule that comes first.
3. Run `./gradlew :core:sms:test`. `MerchantDatasetTest` checks valid categories, compiling patterns and no
   duplicate brands; `CategoryCorpusTest` checks 100 real-world style examples still land correctly.

## Versioning

`schemaVersion` changes only when the format changes. If the app can't read the bundled file, it falls back
to its built-in copy of the rules, so a broken edit can never stop the app from working.
