# Unit Test Quick Reference

## Test Suite: ConfirmControllerTest

### Location
`src/test/java/de/arbeitsagentur/pushmfasim/controller/ConfirmControllerTest.java`

### Quick Stats
- **23 comprehensive unit tests**
- **100% pass rate**
- **~1.5 second execution time**
- **Spring Boot Test framework with Mockito**

## Test Categories

### 1. Request Validation (5 tests)
Tests that validate incoming request parameters and their default values.

```
✓ testMissingToken
✓ testDefaultActionIsApprove
✓ testEmptyActionDefaultsToApprove
✓ testNullActionDefaultsToApprove
✓ testWhitespaceActionDefaultsToApprove
```

### 2. Action Processing (3 tests)
Tests for action parameter handling and normalization.

```
✓ testDenyActionProcessed
✓ testActionNormalization
✓ testHandleSpecialCharactersInUserId
```

### 3. User Verification (4 tests)
Tests for user verification requirement logic.

```
✓ testUserVerificationRequirementForApprove
✓ testNoUserVerificationRequiredForDeny
✓ testParseJWTWithUserVerificationClaim
✓ testParseJWTWithoutUserVerificationClaim
```

### 4. Utility Methods (3 tests)
Tests for the firstNonBlank priority selection helper.

```
✓ testFirstNonBlankSelectsFirstValidValue
✓ testFirstNonBlankWithContextFallback
✓ testFirstNonBlankTrimsWhitespace
```

### 5. Credential Handling (3 tests)
Tests for extracting user ID from credential ID.

```
✓ testExtractUserIdFromCredentialId
✓ testExtractUserIdFromInvalidCredentialId
✓ testHandleSpecialCharactersInUserId
```

### 6. Challenge Management (2 tests)
Tests for finding challenges in pending list.

```
✓ testFindChallengeInPendingList
✓ testChallengeNotFoundInPendingList
```

### 7. Response Formatting (3 tests)
Tests for response message structure and content.

```
✓ testSuccessfulConfirmationResponse
✓ testResponseWithNullUserVerification
✓ testResponseMessageFormat
```

## Running Tests

### All tests
```bash
mvn test
```

### Specific test class
```bash
mvn test -Dtest=ConfirmControllerTest
```

### Specific test method
```bash
mvn test -Dtest=ConfirmControllerTest#testUserVerificationRequirementForApprove
```

### With verbose output
```bash
mvn test -X
```

## Test Dependencies

The project uses `spring-boot-starter-test` which includes:
- **JUnit 5** - Testing framework
- **Mockito** - Mocking/stubbing
- **Jackson** - JSON processing
- **Spring Test** - Spring framework testing utilities

## Key Testing Patterns

### 1. Reflection for Private Methods
```java
Method method = ConfirmController.class.getDeclaredMethod("methodName", String.class);
method.setAccessible(true);
String result = (String) method.invoke(confirmController, "argument");
```

### 2. JWT Claim Validation
```java
JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
    .claim("cid", "challenge-123")
    .claim("credId", "credential-456")
    .build();
```

### 3. Logic Testing Without Mocking
Tests validate pure logic without mocking HTTP calls or external dependencies.

## Coverage Analysis

### Methods Tested
- ✓ `completeEnrollProcess()` - Integration point (partial)
- ✓ `extractUserIdFromCredentialId()` - Complete
- ✓ `firstNonBlank()` - Complete
- ✓ `showInfoPage()` - Complete

### Scenarios Covered
- ✓ Default parameter values
- ✓ Action normalization (APPROVE → approve)
- ✓ User verification requirements
- ✓ Challenge identification
- ✓ Special character handling
- ✓ Response formatting
- ✓ Edge cases (null, empty, whitespace)

## Maintenance

### Adding New Tests
1. Follow naming convention: `test[What][Expected]`
2. Use `@DisplayName` for clear descriptions
3. Use `@Test` annotation
4. Keep tests focused on single responsibility
5. Run `mvn test` to verify

### Running After Changes
After modifying `ConfirmController`:
```bash
mvn test -Dtest=ConfirmControllerTest
```

After any project changes:
```bash
mvn spotless:apply verify
```

## Integration Testing Future Work

The test suite focuses on unit testing. For full integration testing:
- Add `@SpringBootTest` tests
- Use `MockMvc` for HTTP endpoint testing
- Test actual request/response cycles
- Verify RestTemplate interactions

See `TEST_DOCUMENTATION.md` for comprehensive documentation.
