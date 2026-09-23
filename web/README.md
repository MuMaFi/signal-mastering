# Design preview

The Android app's interface, running in a browser, so the design can be tried without
installing an APK. Apple-style throughout: grouped inset lists, a large title that
collapses into the bar as you scroll, UIKit-sized switches and segmented control, SF
Pro on Apple devices and Inter everywhere else.

**The separation is simulated.** The screens, the copy and every number are the app's
own — model sizes, chunk counts, real-time factors and output sizes come from
`ModelCatalog.kt` — but no audio is processed and the clock runs faster. The working
engine is the Android app in [`../android`](../android).

## Start it

Needs Node 18 or newer. No `npm install` — there are no dependencies.

```bash
cd web
npm start            # or: node serve.mjs
```

```
  signal.isolate — design preview

  Local     http://localhost:4173
  Network   http://192.168.x.x:4173
```

Open the **Local** address in a browser on the same machine. The **Network** address
works from any device on the same Wi-Fi, if your firewall allows it. Use a different
port with `PORT=8080 npm start`.

## What to try

- **Choose an audio file.** It is really decoded in the browser, so the length and the
  waveform are the track's own.
- **Switch models.** The stem list, the download size and the footer change with them.
  The four-stem model admits that its stems do not sum back to the mix exactly — they
  don't.
- **Separate.** Watch download → decode → separate → write, with the chunk counter and
  the time estimate the real run would show.
- **Aa** in the top right cycles appearance: system, light, dark.

To jump straight to a screen without picking a file, add `?demo=` to the address:
`downloading`, `decoding`, `separating`, `finalizing` or `done`.

## Contrast

```bash
npm run check
```

Every text/background pair is checked against WCAG AA (4.5:1). The colours are read out
of `app.css` and translucent tokens are composited over the surface they are drawn on —
a secondary label is 76 % grey *over white*, and it is that blend a reader sees. Two
tokens depart from Apple's published palette to pass: the tint (systemMint carries
white text at 2.2:1) and the light secondary label (Apple's 60 % is 3.4:1 on white).

## Files

| | |
| --- | --- |
| `index.html` | Shell and icon sprites |
| `app.css` | Design tokens and components; the Compose theme uses the same values |
| `app.js` | Screens, state and the simulated run |
| `serve.mjs` | Zero-dependency static server |
| `contrast.mjs` | WCAG audit of the colour tokens |
| `fonts/` | Inter, latin subset (71 KB), SIL Open Font License |
