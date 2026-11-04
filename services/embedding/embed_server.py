import logging
import os
import time
from typing import List

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from tokenizers import Tokenizer

MODEL_DIR = os.environ.get("MODEL_DIR", "/opt/embedding/model")
ONNX_PATH = os.path.join(MODEL_DIR, "onnx", "model.onnx")
TOKENIZER_PATH = os.path.join(MODEL_DIR, "tokenizer.json")
MAX_LEN = 512
DIM = 512
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("embedding")

tokenizer = Tokenizer.from_file(TOKENIZER_PATH)
tokenizer.enable_truncation(max_length=MAX_LEN)
tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")

sess = ort.InferenceSession(ONNX_PATH, providers=["CPUExecutionProvider"])
INPUT_NAMES = [i.name for i in sess.get_inputs()]
OUTPUT_NAMES = [o.name for o in sess.get_outputs()]
logger.info("model loaded inputs=%s outputs=%s", INPUT_NAMES, OUTPUT_NAMES)

app = FastAPI(title="DataWorks Embedding Service", version="1.0")


class EmbedRequest(BaseModel):
    texts: List[str]
    is_query: bool = False


def encode(texts: List[str], is_query: bool) -> List[List[float]]:
    if not texts:
        return []
    if is_query:
        texts = [QUERY_PREFIX + t for t in texts]
    encs = tokenizer.encode_batch(texts)
    ids = np.array([e.ids for e in encs], dtype=np.int64)
    mask = np.array([e.attention_mask for e in encs], dtype=np.int64)
    feeds = {"input_ids": ids, "attention_mask": mask}
    if "token_type_ids" in INPUT_NAMES:
        feeds["token_type_ids"] = np.zeros_like(ids)
    feeds = {k: v for k, v in feeds.items() if k in INPUT_NAMES}
    out = sess.run(OUTPUT_NAMES, feeds)[0]
    vec = out[:, 0, :]  # CLS pooling, BGE convention
    norms = np.linalg.norm(vec, axis=1, keepdims=True)
    vec = vec / np.maximum(norms, 1e-12)
    return vec.tolist()


@app.get("/health")
def health():
    return {"status": "ok", "model": "bge-small-zh-v1.5", "dim": DIM}


@app.post("/embed")
def embed(req: EmbedRequest):
    if not req.texts:
        raise HTTPException(status_code=400, detail="texts must not be empty")
    if len(req.texts) > 64:
        raise HTTPException(status_code=400, detail="max 64 texts per request")
    t0 = time.time()
    vectors = encode(req.texts, req.is_query)
    logger.info(
        "embed n=%d is_query=%s ms=%.1f", len(req.texts), req.is_query,
        (time.time() - t0) * 1000,
    )
    return {"dim": DIM, "embeddings": vectors}
