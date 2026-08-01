# Orato

Orato è un'app Android nativa che funziona come coach personale per allenare la comunicazione e il public speaking attraverso brevi esercizi quotidiani.

## Obiettivo

L'utente sceglie uno scenario:

* presentazione davanti al pubblico;
* esame universitario;
* videochiamata online.

L'app avvia la fotocamera e invita l'utente a parlare per 90 secondi. Al termine della sessione genera un report su linguaggio del corpo (presentazione), presenza in video e sguardo (esame / videochiamata), voce e ritmo del discorso.

## Tecnologie

* Kotlin
* Jetpack Compose
* Material 3
* CameraX
* MediaPipe Pose Landmarker
* MediaPipe Face Landmarker
* Supabase Auth
* Supabase Postgres
* Supabase Storage
* Supabase Edge Functions
* OpenAI API tramite backend Supabase

## Funzioni MVP

1. Selezione dello scenario.
2. Sessione video di 90 secondi.
3. Analisi locale della postura.
4. Registrazione dell'audio.
5. Trascrizione speech-to-text.
6. Analisi di parole al minuto, pause e intercalari.
7. Analisi della chiarezza e della struttura del discorso.
8. Report finale con punteggio generale.
9. Confronto con le sessioni precedenti.
10. Sfide giornaliere, livelli e streak.

## Prima milestone

Realizzare una vertical slice che permetta di:

1. aprire l'app;
2. selezionare “Presentazione”;
3. avviare la fotocamera frontale;
4. parlare per 90 secondi;
5. rilevare spalle, busto, gomiti e polsi;
6. visualizzare un report locale con:

   * equilibrio delle spalle;
   * stabilità del busto;
   * visibilità delle mani;
   * affidabilità del rilevamento.

In questa prima milestone non devono essere implementati Supabase e OpenAI.

## Requisiti tecnici

* Applicazione Android nativa.
* Linguaggio Kotlin.
* Interfaccia Jetpack Compose.
* Architettura MVVM.
* Minimum SDK 26.
* Analisi CameraX eseguita fuori dal main thread.
* MediaPipe in modalità `LIVE_STREAM`.
* Nessun caricamento o salvataggio del video.
* Codice compilabile senza metodi placeholder incompleti.
