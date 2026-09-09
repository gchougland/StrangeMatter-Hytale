# Strange Matter 0.3.1

Fixes the Reality Forge client markup error at line8:135. Hytale's LabelAlignment uses Start, Center and End; the two right-aligned Forge labels incorrectly used Right. Both the shipped page and its generator now use End.

The UI validator now checks horizontal and vertical alignment literals in every custom page, ignoring text and comments. Before the fix it reproduced the client's exact line/column failure; after the fix the complete UI audit passes.

Install build/libs/StrangeMatter-0.3.1.jar in place of the previous version. This hotfix changes UI markup and validation, with no model or texture regeneration. Server asset validation does not execute the client's UI parser; the client error supplied in the playtest was used to strengthen the static audit.
