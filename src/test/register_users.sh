
#!/bin/bash

BASE_URL="http://localhost:8080"

for i in {1..5}; do
    EMAIL="customer${i}@gmail.com"
    PASSWORD="123456"

    echo "Registering user: $EMAIL"

    curl -s -X POST "$BASE_URL/auth/register" \
        -H "Content-Type: application/json" \
        -d "{
            \"email\": \"$EMAIL\",
            \"password\": \"$PASSWORD\"
        }" \
        -w "\nHTTP Status: %{http_code}\n"

    echo "----------------------------"
done

echo "Registration completed!"
