# குரலகம் — Tamil Voice Assistant
### Spring Boot + Groq AI · Voice-to-Voice Web App

## Running Locally

### Prerequisites
- Java 17+
- Maven 3.8+
- A Groq API key (https://console.groq.com)

### Steps

```bash
# 1. Set your API key as an environment variable
export GROQ_API_KEY="{GROQ_API_KEY}"

# 2. Build the project
mvn clean package -DskipTests

# 3. Run it
java -jar target/tamil-voice-assistant-1.0.0.jar

# 4. Open your browser
# http://localhost:8080
```

---
