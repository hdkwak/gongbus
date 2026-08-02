# Build stage
FROM rust:1.85-slim-bookworm as builder

WORKDIR /app

# Install build dependencies
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

COPY . .

# CRITICAL FIX: Force specific working versions for transitive dependencies broken in the ecosystem
# We use the full spec (name@version) to avoid the "ambiguous package" error
RUN cargo update -p home@0.5.12 --precise 0.5.11 || true && \
    cargo update -p idna@1.1.0 --precise 0.5.0 || true && \
    cargo build --release

# Run stage
FROM debian:bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y libssl3 ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/running-tracker-api /app/running-tracker-api

EXPOSE 3000
CMD ["./running-tracker-api"]
