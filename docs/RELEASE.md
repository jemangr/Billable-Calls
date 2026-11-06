## Billable Calls 1.3.7

- **Fix:** in 1.3.6 the app closed («keeps stopping») when the «before you answer» card opened. The note field still scrolls, but without a visible scrollbar.

## Billable Calls 1.3.6

- **The note on the «before you answer» card** always shows 4 lines. Longer text scrolls up and down inside the field.
- **The after-call card** has a «🎤 Say the note» button under the note, as well as the small microphone inside the field.

## Billable Calls 1.3.5

- **A note while you talk:** the «before you answer» card has a «Note» field.
  - Tap it and the keyboard opens, even over the call screen. «✓» closes the keyboard.
  - What you write is kept at once. When the call ends, it goes into the call's note, so the after-call card opens already filled in.
  - The card is only for incoming calls from customers, so the note field is too.

## Billable Calls 1.3.4

- **The «before you answer» card** now opens in the middle of the screen.
  - You can drag it up or down. It opens in the same place on the next call.
  - It stays on screen after you answer, until the call ends.
  - You close it by hand with the «✕».

## Billable Calls 1.3.3

- **Fix:** the «before you answer» card sometimes stayed on the screen and wouldn't close. Now only one card shows per call. It closes with a tap or the «✕», when the call ends, or after 60″ at most.
- **Fix:** the screen no longer jumps up and down when you write the note on the after-call card. The card now stays at the bottom, right above the keyboard.
- **The notes on that card:** it shows the company's 2 latest notes (from any of its numbers), with the date and topic, e.g. «03/11 · Υποστήριξη · «…»». If there are none, it shows «Last call: 03/11 · no note».
- **Reminder sound:** Settings → Reminders → «Sound and vibration»:
  - sound on/off and a choice of sound,
  - vibration on/off,
  - «Test», to hear it now,
  - «Android settings», for the channel itself.
  - It warns you if Android has the reminders muted.
- **Call from the call card:** a red «📞 Call» button under Skip/Save, and the number is now tappable. Both ask «Call 69…?».
  - On «Call» the card is saved and the dialer opens.
  - If it's a business call with no company chosen, it only opens the dialer.
  - Not for Viber/WhatsApp calls, which have no number.

## Billable Calls 1.3.2

- **Bug report sent directly:** «Send» delivers it without opening your email app, so your address isn't shown. You can add your email if you want a reply. «From my email» is still there.
- **Shake to report:** shake the phone hard and the bug report opens (Settings → Updates and bugs, can be turned off).
- **New version on the home screen:** when there is one, a dialog shows it with «Install», without going to Settings. Checked at most once an hour when you open the app.

## Billable Calls 1.3.1

- **Fix:** the app closed when the daily Drive backup was turned on, or on screens that set up the background jobs (Android needs a network permission for a job that waits for the internet). It no longer closes, and the backup runs when there is a network.

## Billable Calls 1.3

- **Before you answer:** while the phone rings, a small card shows the company, what's left of its package, the last topics and notes, and the open actions. It needs «display over other apps».
- **Actions and reminders:** the after-call card has «Action needed / reminder», with a text and a date and time if you want one.
  - The reminder's notification has «Call», «Done» and «Later».
  - The «Missed» tab is now «To do», with actions (✓) and missed calls.
- **Companies:** tapping a company shows its actions and its records, each with ✓ «completed», and an All/Open filter.
- **Back-to-back calls:** a call within 20′ of the previous one from the same number or company can be joined to it. The card offers it, and both are billed as one, rounded once. The window is 10/15/20/30′.
- **On site:** an «On site ×1.5» switch on the card.
  - It can also be turned on automatically, when the phone is near the place you saved for the company with «📍 I'm here now».
- **HTML report for the client:** one file that opens offline in any browser, with search and totals per person and per day.
- **Black look** by default. Light, or the same as the phone, in Settings → Look.
- Calls without a topic now count as the default topic (e.g. calls from before 1.1).
