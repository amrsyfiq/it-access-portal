import { Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SYSTEM_CATALOG } from '../../core/system-catalog';

/** "Request access" landing page: a searchable grid of systems. Choosing one opens the form. */
@Component({
  selector: 'app-system-catalog-page',
  imports: [RouterLink],
  templateUrl: './system-catalog-page.html',
  styleUrl: './system-catalog-page.css',
})
export class SystemCatalogPage {
  protected readonly search = signal('');

  protected readonly systems = computed(() => {
    const term = this.search().trim().toLowerCase();
    if (!term) return SYSTEM_CATALOG;
    return SYSTEM_CATALOG.filter(
      (s) => s.name.toLowerCase().includes(term) || s.description.toLowerCase().includes(term),
    );
  });

  protected onSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }
}
