# Build stage
# Use nightly to satisfy high MSRV requirements (1.86+) of new dependency versions
FROM rustlang/rust:nightly-slim-bookworm as builder

WORKDIR /app

# Install build dependencies
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

COPY . .

# Delete the lockfile to ensure the compiler picks versions compatible with this environment
RUN rm -f Cargo.lock && cargo build --release

# Run stage
FROM debian:bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y libssl3 ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/running-tracker-api /app/running-tracker-api

EXPOSE 3000
CMD ["./running-tracker-api"]
