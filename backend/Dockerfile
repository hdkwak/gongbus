# Build stage
FROM rust:1.75-slim-bookworm as builder

WORKDIR /app
COPY . .

# Install build dependencies
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

# Build the application
RUN cargo build --release

# Run stage
FROM debian:bookworm-slim

WORKDIR /app
# Install runtime dependencies
RUN apt-get update && apt-get install -y libssl3 ca-certificates && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/running-tracker-api /app/running-tracker-api
# Copy .env if you use it in production (though Render uses env vars directly)
# COPY .env .env

EXPOSE 3000

CMD ["./running-tracker-api"]
