#!/bin/bash
cd "$(dirname "$0")"
npx vite build && npx electron dist/main/index.js
