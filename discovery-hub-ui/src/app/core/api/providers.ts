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
 * classes stay registered because `HttpCaseApi` still delegates to one: case
 * custodians and single-item evidence removal have nowhere to be stored,
 * because the case service models communications rather than people.
 * `environment.mockBacked` is the authoritative list.
 *
 * No component imports a concrete implementation, so flipping the switch
 * changes nothing above this layer.
 */
/** The mock implementations, registered so the HTTP classes can delegate. */
const MOCKS: Provider[] = [
  MockSearchApi,
  MockCaseApi,
  MockHoldApi,
  MockExportApi,
  MockAuditApi,
  MockPlatformApi,
];

/**
 * Binds every contract to the in-memory backend.
 *
 * Exported for tests. Component specs must not go through
 * {@link provideDiscoveryHubApi}: that resolves to the HTTP clients, which
 * need an `HttpClient` the TestBed does not provide, and every page degrades
 * to an error band instead of rendering — so the specs would pass while
 * asserting nothing.
 */
export function provideMockDiscoveryHubApi(): Provider[] {
  return [
    ...MOCKS,
    { provide: SearchApi, useExisting: MockSearchApi },
    { provide: CaseApi, useExisting: MockCaseApi },
    { provide: HoldApi, useExisting: MockHoldApi },
    { provide: ExportApi, useExisting: MockExportApi },
    { provide: AuditApi, useExisting: MockAuditApi },
    { provide: PlatformApi, useExisting: MockPlatformApi },
  ];
}

export function provideDiscoveryHubApi(): Provider[] {
  const mocks = MOCKS;

  if (environment.useMockBackend) {
    return provideMockDiscoveryHubApi();
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
