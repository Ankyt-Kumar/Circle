-- Optional: install pgvector for your Postgres.app PostgreSQL version first.
-- Core features work without this migration; For you falls back to nearby.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS circle_embeddings (
 circle_id text PRIMARY KEY REFERENCES circles(id) ON DELETE CASCADE,
 model text NOT NULL, content_hash text NOT NULL, embedding vector(768) NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS circle_embeddings_hnsw ON circle_embeddings USING hnsw(embedding vector_cosine_ops);
