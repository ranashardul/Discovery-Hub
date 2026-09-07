# Search Service

## Project Structure

```text
search-service/
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/stown/search/
│   │   │       │
│   │   │       ├── SearchServiceApplication.java
│   │   │       │
│   │   │       ├── controller/
│   │   │       │   └── SearchController.java
│   │   │       │
│   │   │       ├── service/
│   │   │       │   └── SearchService.java
│   │   │       │
│   │   │       ├── repository/
│   │   │       │   └── SearchRepository.java
│   │   │       │
│   │   │       ├── model/
│   │   │       │   └── SearchDocument.java
│   │   │       │
│   │   │       ├── dto/
│   │   │       │   ├── SearchRequest.java
│   │   │       │   └── SearchResponse.java
│   │   │       │
│   │   │       ├── config/
│   │   │       │   └── ElasticsearchConfig.java
│   │   │       │
│   │   │       └── exception/
│   │   │           └── GlobalExceptionHandler.java
│   │   │
│   │   └── resources/
│   │       └── application.yml
│   │
│   └── test/
│
├── Dockerfile
├── pom.xml
└── README.md
```

## How the Service Works

The Search Service will use Spring Boot + Elasticsearch to provide search functionality for S-Town.

The basic flow will be:


```text
User / Client
      │
      │ Search Request
      ▼
Search Controller
      │
      ▼
Search Service
      │
      ▼
Search Repository
      │
      │ Elasticsearch Query
      ▼
Elasticsearch
      │
      │ Search Results
      ▼
Search Service
      │
      ▼
User / Client
```

## Data Flow

- Data will first enter S-Town through the Ingestion Service.
- After processing, the relevant searchable data will be indexed into Elasticsearch.


The Search Service will then query Elasticsearch whenever a user performs a search.

```text
Ingestion Service
       │
       │ Index searchable data
       ▼
Elasticsearch
       ▲
       │ Search
       │
Search Service
       ▲
       │
     Client
```

Elasticsearch will act as the search index, while the original/archived data remains in the appropriate source-of-truth storage.

The initial version will support basic full-text search, with filters, pagination, and advanced search capabilities added incrementally.