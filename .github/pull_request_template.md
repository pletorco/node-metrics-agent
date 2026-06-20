## Summary

## Module Classification

- [ ] Telemetry module
- [ ] Build/release/documentation only

## Host Safety

- [ ] Host/JMX read paths remain bounded and non-blocking
- [ ] Fail-open behavior is preserved
- [ ] Queues/maps/caches have hard limits
- [ ] Internal logs are throttled where repeated failures are possible

## Tests

- [ ] `./gradlew test`
- [ ] `./gradlew checkstyleMain checkstyleTest`
- [ ] Other:

## Rollback Notes
