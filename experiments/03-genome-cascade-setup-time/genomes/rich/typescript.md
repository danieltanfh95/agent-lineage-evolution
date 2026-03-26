# TypeScript Genome — Language-Specific Knowledge

## TypeScript Conventions
- Always enable strict mode in tsconfig.json (`"strict": true`)
- Prefer explicit type annotations on function signatures
- Use interfaces over type aliases for object shapes that may be extended
- Prefer `const` over `let`; never use `var`
- Use template literals over string concatenation

## Project Setup
- Initialize with `npm init -y` then add TypeScript dependencies
- Required devDependencies: typescript, @types/node, ts-node or tsx
- tsconfig.json should include: strict, esModuleInterop, skipLibCheck, outDir
- Use `"module": "commonjs"` for Node.js projects unless using ESM

## Error Handling
- Always type error parameters in catch blocks
- Use discriminated unions for result types when appropriate
- Never use `any` for error types — use `unknown` and narrow
- Async functions should always have try-catch or propagate errors explicitly

## Common Patterns
- Use Zod or similar for runtime validation of external input
- Prefer named exports over default exports
- Group imports: node builtins, external packages, local modules
- Use path aliases for deep imports when project grows

## Testing
- Prefer vitest for TypeScript projects (native TS support)
- Use describe/it/expect pattern
- Mock external dependencies, test business logic directly
