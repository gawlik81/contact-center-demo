/**
 * Single source for the product name shown in the UI.
 *
 * The final product name/logo is an open decision (see PRODUCT.md), so the working name lives
 * here only and is imported wherever it is rendered. Do not hardcode it in templates.
 *
 * Note: `index.html` `<title>ContactCenterFrontend</title>` is the working build name, left
 * as is until the brand decision is made (PRODUCT.md, open decisions); it is intentionally
 * not tied to `BRAND.name`.
 */
export const BRAND = {
  name: 'Contact Center',
  vendor: 'KMN Software',
} as const;
