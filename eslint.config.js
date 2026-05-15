import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
    {
        ignores: ["dist/**", "dist-mcpb/**", "coverage/**"],
    },
    js.configs.recommended,
    tseslint.configs.recommended,
    {
        files: ["src/**/*.ts"],
        languageOptions: {
            sourceType: "module",
            ecmaVersion: 2022,
        },
        rules: {
            // Empty catch blocks are an intentional best-effort-cleanup
            // pattern here (e.g. `try { file.close(); } catch {}`).
            "no-empty": ["error", { allowEmptyCatch: true }],
            // `any` is pragmatic at the sql.js boundary (untyped query
            // rows) and for generic tool-handler dispatch — kept visible
            // as a warning rather than blocking the build.
            "@typescript-eslint/no-explicit-any": "warn",
            // Allow `_`-prefixed identifiers as intentionally-unused.
            "@typescript-eslint/no-unused-vars": ["error", {
                argsIgnorePattern: "^_",
                varsIgnorePattern: "^_",
                caughtErrorsIgnorePattern: "^_",
            }],
        },
    },
);
