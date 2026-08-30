FROM python:3.11-slim

WORKDIR /app

# Install system dependencies for OpenCV and video handling
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy dependency definition
COPY requirements.txt .

# Install python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy source code and models
COPY src/ ./src/
COPY models/ ./models/

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

CMD ["python", "-m", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
