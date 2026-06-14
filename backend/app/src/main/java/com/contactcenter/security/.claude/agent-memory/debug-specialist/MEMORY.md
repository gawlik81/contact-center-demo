# Memory Index

- [Circular bean dependency chain during domain refactor](circular_bean_dependencies_refactor.md) — 5 chained Spring DI cycles fixed on branch `refactor`, expect more if new deps added near ContactService/UserService/TenantService
- [@Lazy setter convention](lazy_setter_convention.md) — how this codebase breaks Spring circular bean dependencies (setter + @Lazy @Autowired, not allow-circular-references)
