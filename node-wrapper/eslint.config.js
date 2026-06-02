// ESLint flat config for the node-wrapper (ESM, Node >= 22).
import js from "@eslint/js";
import globals from "globals";

export default [
  { ignores: ["node_modules/**", "certs/**"] },
  js.configs.recommended,
  {
    files: ["**/*.{js,mjs}"],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: "module",
      globals: { ...globals.node },
    },
    rules: {
      "no-unused-vars": ["warn", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "no-console": "off",
      eqeqeq: ["warn", "smart"],
      "prefer-const": "warn",
    },
  },
];
