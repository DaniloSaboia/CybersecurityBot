// icons.jsx — minimal line-icon set, security console flavored.
// Each icon is a 24x24 stroke SVG; size/color inherit via props.

export const ICON_PATHS = {
  // brand mark — a stylized shield/scan
  shield: <><path d="M12 2 4 5v6c0 5 3.5 8.5 8 11 4.5-2.5 8-6 8-11V5l-8-3Z" /><path d="M8.5 12l2.5 2.5 4.5-5" /></>,
  trash: <><path d="M4 7h16" /><path d="M10 4h4a1 1 0 0 1 1 1v2H9V5a1 1 0 0 1 1-1Z" /><path d="M6 7l1 13a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-13" /><path d="M10 11v6M14 11v6" /></>,
  chat: <><path d="M4 5h16v11H9l-4 3v-3H4V5Z" /><path d="M8 9.5h8M8 12.5h5" /></>,
  book: <><path d="M5 4h10a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3V4Z" /><path d="M5 17a3 3 0 0 1 3-3h10" /><path d="M9 8h6M9 11h4" /></>,
  ingest: <><path d="M12 3v10" /><path d="m8 9 4 4 4-4" /><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></>,
  grid: <><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-3.2-3.2" /></>,
  alert: <><path d="M12 3 2 20h20L12 3Z" /><path d="M12 10v5M12 17.5v.5" /></>,
  intel: <><circle cx="12" cy="12" r="3.2" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2" /></>,
  doc: <><path d="M7 3h7l4 4v14H7V3Z" /><path d="M14 3v4h4" /><path d="M10 12h5M10 15.5h5M10 8.5h2" /></>,
  send: <><path d="M4 12 20 4l-6 16-3-7-7-1Z" /></>,
  link: <><path d="M9 15 15 9" /><path d="M11 6.5 13 4.5a3.5 3.5 0 0 1 5 5l-2 2" /><path d="M13 17.5 11 19.5a3.5 3.5 0 0 1-5-5l2-2" /></>,
  bolt: <><path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" /></>,
  upload: <><path d="M12 16V5" /><path d="m7 10 5-5 5 5" /><path d="M4 18v1a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-1" /></>,
  check: <><path d="m4 12 5 5L20 6" /></>,
  x: <><path d="M6 6l12 12M18 6 6 18" /></>,
  refresh: <><path d="M20 11a8 8 0 0 0-14-4l-2 2" /><path d="M4 13a8 8 0 0 0 14 4l2-2" /><path d="M4 5v4h4M20 19v-4h-4" /></>,
  database: <><ellipse cx="12" cy="5.5" rx="7" ry="2.8" /><path d="M5 5.5v6c0 1.5 3.1 2.8 7 2.8s7-1.3 7-2.8v-6" /><path d="M5 11.5v6c0 1.5 3.1 2.8 7 2.8s7-1.3 7-2.8v-6" /></>,
  cpu: <><rect x="6" y="6" width="12" height="12" rx="2" /><rect x="9.5" y="9.5" width="5" height="5" rx="0.6" /><path d="M9 3v2M15 3v2M9 19v2M15 19v2M3 9h2M3 15h2M19 9h2M19 15h2" /></>,
  filter: <><path d="M4 5h16l-6 7v5l-4 2v-7L4 5Z" /></>,
  clock: <><circle cx="12" cy="12" r="8" /><path d="M12 7.5V12l3 2" /></>,
  hash: <><path d="M5 9h14M5 15h14M10 4 8 20M16 4l-2 16" /></>,
  globe: <><circle cx="12" cy="12" r="8" /><path d="M4 12h16M12 4c2.5 2 2.5 14 0 16M12 4c-2.5 2-2.5 14 0 16" /></>,
  user: <><circle cx="12" cy="8" r="3.4" /><path d="M5 20c0-3.4 3.1-5.5 7-5.5s7 2.1 7 5.5" /></>,
  chevron: <><path d="m9 6 6 6-6 6" /></>,
  arrowleft: <><path d="M19 12H5" /><path d="m11 6-6 6 6 6" /></>,
  copy: <><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" /></>,
  dot: <circle cx="12" cy="12" r="3" />,
  arrowdown: <><path d="M12 5v14" /><path d="m6 13 6 6 6-6" /></>,
  arrowright: <><path d="M5 12h14" /><path d="m13 6 6 6-6 6" /></>,
  s3: <><path d="M12 3 4 6.5v8L12 18l8-3.5v-8L12 3Z" /><path d="m4 6.5 8 3.5 8-3.5M12 10v8" /></>,
  api: <><path d="M7 8 3 12l4 4M17 8l4 4-4 4M14 5l-4 14" /></>,
  url: <><circle cx="12" cy="12" r="8" /><path d="M8 12a4 4 0 0 1 4-4M16 12a4 4 0 0 1-4 4" /></>,
  menu: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" /></>,
  sliders: <><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6" /></>,
  key: <><path d="m21 2-2 2m-1.5 1.5L19 7l-2 2-2-2m-3.5 3.5a5 5 0 1 1 7-7 5 5 0 0 1-7 7Z" /></>,
  eye: <><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z" /><circle cx="12" cy="12" r="3" /></>,
  eyeOff: <><path d="M9.88 9.88a3 3 0 1 0 4.24 4.24M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61M2 2l20 20" /></>,
  thumbsUp: <><path d="M7 10v12M15 10.5V6a3 3 0 0 0-3-3l-2.5 7H3a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h14a2 2 0 0 0 2-1.6l1.5-7.5A2 2 0 0 0 18.5 10.5H15Z" /></>,
  thumbsDown: <><path d="M17 14V2M9 13.5V18a3 3 0 0 0 3 3l2.5-7H21a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H7a2 2 0 0 0-2 1.6L3.5 11.1A2 2 0 0 0 5.5 13.5H9Z" /></>,
  terminal: <><path d="m4 17 6-6-6-6M12 19h8" /></>,
  sun: <><circle cx="12" cy="12" r="4.2" /><path d="M12 2v2.2M12 19.8V22M4 12H1.8M22.2 12H20M5.6 5.6 4 4M20 20l-1.6-1.6M18.4 5.6 20 4M4 20l1.6-1.6" /></>,
  moon: <><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z" /></>,
  bell: <><path d="M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" /><path d="M10.3 19a2 2 0 0 0 3.4 0" /></>,
  command: <><path d="M9 6a2.5 2.5 0 1 0-2.5 2.5H9V6Z" /><path d="M15 6a2.5 2.5 0 1 1 2.5 2.5H15V6Z" /><path d="M9 18a2.5 2.5 0 1 1-2.5-2.5H9V18Z" /><path d="M15 18a2.5 2.5 0 1 0 2.5-2.5H15V18Z" /><path d="M9 8.5h6M9 15.5h6M9 8.5v7M15 8.5v7" /></>,
  download: <><path d="M12 3v11" /><path d="m7.5 9.5 4.5 4.5 4.5-4.5" /><path d="M4 18v1a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-1" /></>,
  radar: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><path d="M12 12 18 7" /><circle cx="12" cy="12" r="1" /></>,
};

export function Icon({ name, size = 18, stroke = 1.6, className, style }) {
  const paths = ICON_PATHS[name] || ICON_PATHS.dot;
  return (
    <svg
      width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth={stroke}
      strokeLinecap="round" strokeLinejoin="round"
      className={className} style={style} aria-hidden="true"
    >
      {paths}
    </svg>
  );
}
