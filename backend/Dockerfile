# Build stage
FROM rust:1.85-slim-bookworm as builder

WORKDIR /app

# Install build dependencies
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

COPY . .

# CRITICAL FIX: Force downgrade of several broken crates that require unreleased Rust versions
RUN cargo update -p home --precise 0.5.11 && \
    cargo update -p icu_locid --precise 1.5.0 && \
    cargo update -p icu_collections --precise 1.5.0 && \
    cargo update -p icu_properties --precise 1.5.0 && \
    cargo update -p icu_provider --precise 1.5.0 && \
    cargo update -p icu_normalizer --precise 1.5.0 && \
    cargo update -p idna --precise 0.5.0 && \
    cargo build --release

# Run stage
FROM debian:bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y libssl3 ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/running-tracker-api /app/running-tracker-api

EXPOSE 3000
CMD ["./running-tracker-api"]
