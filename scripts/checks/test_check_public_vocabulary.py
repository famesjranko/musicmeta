#!/usr/bin/env python3
"""Self-check for check_public_vocabulary.py.

A gate nobody has watched fail is not a gate. Every case here is either a borrowed word the rule
exists to catch, or one of the four the real surface carries today — each of which is legal for a
different reason, and each of which would be a false positive if the attribution logic broke.
Run with: python3 test_check_public_vocabulary.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_public_vocabulary import run  # noqa: E402

DUMP = "musicmeta-core/api/musicmeta-core.api"


class PublicVocabularyTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a file at a repo-relative path."""
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, body: str, *, rel: str = DUMP) -> list[str]:
        """Findings for a tree holding one `api/*.api` with this body."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, rel, body)
            return run(root)

    # --- the rule fires ---

    def test_unqualified_recording_member_is_reported(self):
        # Given a public accessor that borrows MusicBrainz's word for a track
        body = (
            "public final class com/landofoz/musicmeta/TrackProfile {\n"
            "\tpublic final fun getRecordingId ()Ljava/lang/String;\n"
            "}\n"
        )
        # When the vocabulary check runs
        findings = self.findings_for(body)
        # Then it names the borrowed word and this library's word for the same thing
        self.assertEqual(len(findings), 1)
        self.assertIn("getRecordingId", findings[0])
        self.assertIn("`track`", findings[0])

    def test_unqualified_master_member_is_reported(self):
        # Given Discogs' word for an album on a public type
        body = (
            "public final class com/landofoz/musicmeta/AlbumProfile {\n"
            "\tpublic final fun getMasterId ()Ljava/lang/Long;\n"
            "}\n"
        )
        # When the vocabulary check runs
        findings = self.findings_for(body)
        # Then it is reported against `album`
        self.assertEqual(len(findings), 1)
        self.assertIn("`album`", findings[0])

    def test_unqualified_type_name_is_reported(self):
        # Given a borrowed word in the declaration itself rather than a member
        body = "public final class com/landofoz/musicmeta/ReleaseGroupSummary {\n}\n"
        # When the vocabulary check runs
        findings = self.findings_for(body)
        # Then the type is reported — a public name freezes under the compatibility promise
        self.assertEqual(len(findings), 1)
        self.assertIn("ReleaseGroupSummary", findings[0])

    def test_underscored_stem_is_reported(self):
        # Given the same word spelled as an enum constant, which a naive substring match misses
        body = (
            "public final class com/landofoz/musicmeta/Namespace : java/lang/Enum {\n"
            "\tpublic static final field RELEASE_GROUP Lcom/landofoz/musicmeta/Namespace;\n"
            "}\n"
        )
        # When the vocabulary check runs
        # Then the separators do not hide it
        self.assertEqual(len(self.findings_for(body)), 1)

    # --- the four the real surface carries, each legal for its own reason ---

    def test_provider_qualified_member_is_allowed(self):
        # Given `getMusicBrainzReleaseGroupId` — the borrowed word attributed in the name itself
        body = (
            "public final class com/landofoz/musicmeta/EnrichmentIdentifiers {\n"
            "\tpublic final fun getMusicBrainzReleaseGroupId ()Ljava/lang/String;\n"
            "}\n"
        )
        # When the vocabulary check runs
        # Then it is allowed: it says whose entity it is
        self.assertEqual(self.findings_for(body), [])

    def test_member_qualified_by_its_enclosing_type_is_allowed(self):
        # Given `MusicBrainzEntityType.RECORDING` — the attribution is on the type, and requiring
        # it twice would force `MusicBrainzEntityType.MUSICBRAINZ_RECORDING`
        body = (
            "public final class com/landofoz/musicmeta/MusicBrainzEntityType : java/lang/Enum {\n"
            "\tpublic static final field RECORDING Lcom/landofoz/musicmeta/MusicBrainzEntityType;\n"
            "}\n"
        )
        # When the vocabulary check runs
        # Then it is allowed
        self.assertEqual(self.findings_for(body), [])

    def test_qualified_enum_constant_is_allowed(self):
        # Given `MUSICBRAINZ_RECORDING` on a type that does not itself name the provider
        body = (
            "public final class com/landofoz/musicmeta/IdentifierNamespace : java/lang/Enum {\n"
            "\tpublic static final field MUSICBRAINZ_RECORDING Lcom/landofoz/musicmeta/IdentifierNamespace;\n"
            "}\n"
        )
        # When the vocabulary check runs
        # Then the constant's own qualifier is enough
        self.assertEqual(self.findings_for(body), [])

    def test_a_supertype_list_does_not_hide_the_declared_name(self):
        # Given a borrowed type name followed by ` : java/lang/Enum {`. Reading the *last*
        # `/`-bearing token attributes the line to `Enum` and the finding disappears.
        body = "public final class com/landofoz/musicmeta/RecordingKind : java/lang/Enum {\n}\n"
        # When the vocabulary check runs
        # Then the declared type is what is judged
        findings = self.findings_for(body)
        self.assertEqual(len(findings), 1)
        self.assertIn("RecordingKind", findings[0])

    # --- descriptors are not identifiers ---

    def test_a_collection_descriptor_is_not_a_finding(self):
        # Given a member returning `java/util/Collection`. `collection` is iTunes' word for an
        # album and is deliberately not banned: erased descriptors put it on a large share of
        # lines, and a rule that fires everywhere gets turned off.
        body = (
            "public final class com/landofoz/musicmeta/AlbumProfile {\n"
            "\tpublic final fun getTracks ()Ljava/util/Collection;\n"
            "}\n"
        )
        # When the vocabulary check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for(body), [])

    def test_a_borrowed_word_in_a_descriptor_only_is_not_a_finding(self):
        # Given a legally-named member whose *parameter* is a provider type carrying the word.
        # Only identifiers are judged; the descriptor is the other type's business.
        body = (
            "public final class com/landofoz/musicmeta/AlbumProfile {\n"
            "\tpublic final fun getTitle (Lcom/landofoz/musicmeta/MusicBrainzRecording;)V\n"
            "}\n"
        )
        # When the vocabulary check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for(body), [])

    # --- the scan cannot go quiet ---

    def test_no_api_dump_at_all_is_reported(self):
        # Given a tree with no baseline — a rename, a module move, or a dump nobody generated.
        # The scan would otherwise report the same "clean" as a full run.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "README.md", "text\n")
            # When the vocabulary check runs
            findings = run(root)
        # Then the silence itself is the finding
        self.assertEqual(len(findings), 1)
        self.assertIn("checked nothing", findings[0])

    def test_a_worktree_dump_is_not_scanned(self):
        # Given a violation in an agent worktree's baseline, which is another branch's surface
        body = "public final class com/landofoz/musicmeta/RecordingKind {\n}\n"
        # When the vocabulary check runs
        findings = self.findings_for(body, rel=f".claude/worktrees/agent-a1/{DUMP}")
        # Then only the missing real baseline is reported, never that branch's name
        self.assertEqual(len(findings), 1)
        self.assertIn("checked nothing", findings[0])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
