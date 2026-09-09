import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { provideDiscoveryHubApi } from './core/api/providers';

describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideDiscoveryHubApi()],
    }).compileComponents();
  });

  it('creates the shell', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders every primary destination', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const labels = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.nav__item'),
    ).map((item) => item.textContent?.trim());

    expect(labels).toEqual([
      'Dashboard',
      'Search',
      'Cases',
      'Legal holds',
      'Exports',
      'Retention',
      'Audit trail',
    ]);
  });
});
