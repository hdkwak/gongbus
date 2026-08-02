# Build stage
FROM rust:1.81-slim-bookworm as builder

WORKDIR /app

# Optimization: Pre-install build dependencies
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

# Copy only the dependency files first to cache them
COPY Cargo.toml Cargo.lock ./
# Create a dummy main.rs to build dependencies only
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release && rm -rf src

# Now copy the actual source code and build the real app
COPY src ./src
RUN cargo build --release

# Run stage
FROM debian:bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y libssl3 ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/running-tracker-api /app/running-tracker-api

EXPOSE 3000
CMD ["./running-tracker-api"]
