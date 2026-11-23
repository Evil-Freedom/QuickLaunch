FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# 先只拷 requirements：依赖层可复用，改业务代码不必重装依赖
COPY requirements.txt .
# 国内部署默认走阿里云镜像；海外可 --build-arg PIP_INDEX=https://pypi.org/simple/
ARG PIP_INDEX=https://mirrors.aliyun.com/pypi/simple/
RUN pip install --no-cache-dir -i ${PIP_INDEX} -r requirements.txt

COPY app ./app

# 非 root 运行；数据目录由 compose 挂载并授权
RUN useradd -r -u 10001 -m ql && mkdir -p /data && chown -R ql:ql /data /app
USER ql

EXPOSE 8000

# 单 worker：SQLite 写入靠 SQLite 自身的锁串行化，
# 多 worker 反而会在 WAL checkpoint 上互相竞争，本场景没有任何收益。
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips", "*"]
