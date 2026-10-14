declare module 'leaflet' {
  type Point = [number, number]
  export interface Map { remove(): void; fitBounds(bounds: Point[], options?: object): Map }
  export function map(element: HTMLElement): Map
  export function tileLayer(url: string, options?: object): { addTo(map: Map): void }
  export function polyline(points: Point[], options?: object): { addTo(map: Map): void }
  export function marker(point: Point): { addTo(map: Map): { bindTooltip(text: string): void } }
  const leaflet: { map: typeof map; tileLayer: typeof tileLayer; polyline: typeof polyline; marker: typeof marker }
  export default leaflet
}
