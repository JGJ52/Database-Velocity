# Database-Velocity

This plugin is made for my other plugins to make it easier for me to use PostgreSQL and Redis in my plugins.

Here's how to include it in **your** project:

**Maven:**\
Add this to repositories:
```xml
<repository>
    <id>jgj52-repo</id>
    <url>https://maven.jgj52.hu/repository/maven-releases/</url>
</repository>
```

And this to dependencies:
```xml
<dependency>
    <groupId>hu.jgj52</groupId>
    <artifactId>database-velocity</artifactId>
    <version>1.0</version>
    <scope>provided</scope>
</dependency>
```
**Gradle:**
- **Groovy:**
  ```gradle
  repositories {
    maven {
        url "https://maven.jgj52.hu/repository/maven-releases/"
    }
  }
  ```
  ```gradle
  dependencies {
    compileOnly "hu.jgj52:database-velocity:1.0"
  }
  ```
- **Kotlin:**
  ```kotlin
  repositories {
    maven {
        url = uri("https://maven.jgj52.hu/repository/maven-releases/")
    }
  }
  ```
  ```kotlin
  dependencies {
    compileOnly("hu.jgj52:database-velocity:1.0")
  }
  ```