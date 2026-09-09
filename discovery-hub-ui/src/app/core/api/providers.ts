import { Provider } from '@angular/core';
import { environment } from '../../../environments/environment';
import {
  MockAuditApi,
  MockCaseApi,
  MockExportApi,
  MockHoldApi,
  MockPlatformApi,
  MockSearchApi,
} from '../mock/mock-apis';
import { AuditApi } from './audit-api';
import { CaseApi } from './case-api';
import { ExportApi } from './export-api';
import { HttpAuditApi } from './http/http-audit-api';
import { HttpCaseApi } from './http/http-case-api';
import { HttpExportApi } from './http/http-export-api';
import { HttpHoldApi } from './http/http-hold-api';
import { HttpPlatformApi } from './http/http-platform-api';
import { HttpSearchApi } from './http/http-search-api';
import { HoldApi } from './hold-api';
import { PlatformApi } from './platform-api';
import { SearchApi } from './search-api';

/**
 * Binds the API contracts to an implementation.
 *
 * With `useMockBackend` false the app talks HTTP to the services. The mock
 * classes stay registered because the HTTP implementations delegate to them
 * for the capabilities no service exposes — saved searches, custodians, hold
 * scope preview, retention policy editing, the export manifest and audit
 * filtering. `environment.mockBacked` is the authoritative list.
 *
 * No component imports a concrete implementation, so flipping the switch
 * changes nothing above this layer.
 */
export function provideDiscoveryHubApi(): Provider[] {
  // Always available: the HTTP implementations inject these as a fallback.
  const mocks: Provider[] = [
    MockSearchApi,
    MockCaseApi,
    MockHoldApi,
    MockExportApi,
    MockAuditApi,
    MockPlatformApi,
  ];

  if (environment.useMockBackend) {
    return [
      ...mocks,
      { provide: SearchApi, useExisting: MockSearchApi },
      { provide: CaseApi, useExisting: MockCaseApi },
      { provide: HoldApi, useExisting: MockHoldApi },
      { provide: ExportApi, useExisting: MockExportApi },
      { provide: AuditApi, useExisting: MockAuditApi },
      { provide: PlatformApi, useExisting: MockPlatformApi },
    ];
  }

  return [
    ...mocks,
    { provide: SearchApi, useClass: HttpSearchApi },
    { provide: CaseApi, useClass: HttpCaseApi },
    { provide: HoldApi, useClass: HttpHoldApi },
    { provide: ExportApi, useClass: HttpExportApi },
    { provide: AuditApi, useClass: HttpAuditApi },
    { provide: PlatformApi, useClass: HttpPlatformApi },
  ];
}
