# Build stage
FROM node:22-alpine AS build
WORKDIR /build

# package.json and the lockfile only, so a source-only edit reuses the
# dependency layer instead of reinstalling on every build.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Runtime stage
FROM nginx:1.27-alpine

RUN apk add --no-cache curl

# The Angular build emits the browser bundle into dist/<project>/browser.
COPY --from=build /build/dist/discovery-hub-ui/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
