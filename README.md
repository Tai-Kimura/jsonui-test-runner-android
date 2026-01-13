# JsonUI Test Runner for Android

Cross-platform UI test runner for Android using Espresso and UI Automator.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    androidTestImplementation("com.jsonui:testrunner:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    androidTestImplementation 'com.jsonui:testrunner:1.0.0'
}
```

### JitPack

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency:

```kotlin
dependencies {
    androidTestImplementation("com.github.Tai-Kimura:jsonui-test-runner-android:1.0.0")
}
```

## Usage

### Basic Usage

```kotlin
@RunWith(AndroidJUnit4::class)
class MyUITest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun testFromAssets() {
        // Load test from assets
        val test = JsonUITest.loadFromAssets(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "tests/home_screen.test.json"
        )

        // Create runner and execute
        val runner = JsonUITest.createRunner()
        val result = runner.run(test)

        // Assert all tests passed
        assertTrue(result.allPassed)
    }
}
```

### Custom Configuration

```kotlin
val runner = JsonUITest.runnerBuilder()
    .defaultTimeout(10000L)
    .screenshotOnFailure(true)
    .verbose(true)
    .build()

val result = runner.run(test)
```

### Loading Tests

```kotlin
// From assets
val test = JsonUITest.loadFromAssets(context, "tests/login.test.json")

// From file path
val test = JsonUITest.load("/sdcard/tests/login.test.json")

// From JSON string
val test = JsonUITest.loadFromString(jsonString, "my_test")

// Load all tests from assets directory
val tests = JsonUITest.loadAllFromAssets(context, "tests")
```

### Running Multiple Tests

```kotlin
@Test
fun runAllTests() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val tests = JsonUITest.loadAllFromAssets(context, "tests")
    val runner = JsonUITest.createRunner()

    for (test in tests) {
        val result = runner.run(test)
        println("${test.metadata.name}: ${if (result.allPassed) "PASSED" else "FAILED"}")
        assertTrue("Test ${test.metadata.name} failed", result.allPassed)
    }
}
```

## Test File Format

Test files use JSON format with `.test.json` extension.

### Screen Test Example

```json
{
  "type": "screen",
  "source": {
    "layout": "layouts/login.json"
  },
  "metadata": {
    "name": "login_screen_test",
    "description": "Tests for login screen"
  },
  "platform": "android",
  "cases": [
    {
      "name": "successful_login",
      "steps": [
        { "action": "waitFor", "id": "login_screen", "timeout": 5000 },
        { "action": "input", "id": "email_field", "value": "test@example.com" },
        { "action": "input", "id": "password_field", "value": "password123" },
        { "action": "tap", "id": "login_button" },
        { "assert": "visible", "id": "home_screen", "timeout": 10000 }
      ]
    }
  ]
}
```

## Supported Actions

| Action | Required | Optional | Description |
|--------|----------|----------|-------------|
| tap | id | timeout | Tap on an element |
| doubleTap | id | timeout | Double tap |
| longPress | id | timeout | Long press |
| input | id, value | timeout | Input text |
| clear | id | timeout | Clear text field |
| scroll | id, direction | timeout | Scroll |
| swipe | id, direction | timeout | Swipe gesture |
| waitFor | id | timeout | Wait for element |
| waitForAny | ids | timeout | Wait for any element |
| wait | ms | - | Wait duration |
| back | - | - | Navigate back |
| screenshot | name | - | Take screenshot |

**Direction values:** `up`, `down`, `left`, `right`

## Supported Assertions

| Assert | Required | Optional | Description |
|--------|----------|----------|-------------|
| visible | id | timeout | Element is visible |
| notVisible | id | timeout | Element is not visible |
| enabled | id | timeout | Element is enabled |
| disabled | id | timeout | Element is disabled |
| text | id | equals, contains, timeout | Text matches |
| count | id, equals | timeout | Element count |

## Element Identification

Elements are identified using `contentDescription` (accessibility label). Make sure your views have proper content descriptions:

```xml
<Button
    android:id="@+id/login_button"
    android:contentDescription="login_button"
    ... />
```

Or in Jetpack Compose:

```kotlin
Button(
    onClick = { /* ... */ },
    modifier = Modifier.semantics { contentDescription = "login_button" }
) {
    Text("Login")
}
```

## Requirements

- Android SDK 24+
- Kotlin 1.9+
- AndroidX Test libraries

## License

MIT License
