// Auto-generated from config/i18n/*.json. Do not edit manually.
// This file provides TypeScript types inferred directly from JSON config files

// Import the JSON file to get both runtime values and type information
import enTranslations from "../../../config/i18n/en_US.json";

// Create a type that includes all keys from the JSON file
export type I18nKeyType = keyof typeof enTranslations;

// Create a runtime object that maps keys to themselves for compile-time safety
// This ensures that I18nKey.foo === "foo" for all keys, providing autocomplete
export const I18nKey: Record<I18nKeyType, I18nKeyType> = Object.fromEntries(
  Object.keys(enTranslations).map((key) => [key, key]),
) as Record<I18nKeyType, I18nKeyType>;
