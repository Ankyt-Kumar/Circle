FROM golang:1.24-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -mod=readonly -buildvcs=false -o /circle-api ./cmd/api

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 circle
COPY --from=build /circle-api /usr/local/bin/circle-api
USER circle
EXPOSE 8080
ENTRYPOINT ["circle-api"]
