package com.rabitah.backend.academic;
import static org.junit.jupiter.api.Assertions.*; import org.junit.jupiter.api.Test;
class StudentRosterTest {
 @Test void normalizesCaseAndRepeatedSpaces(){var r=new StudentRoster("230041211","Student 230041211","CSE","B",2);assertTrue(r.matches("  STUDENT   230041211 ","cse","b",2));assertFalse(r.matches("Student 230041212","CSE","B",2));}
 @Test void buildsAndParsesTheNumericCampusCode(){String id=StudentIdScheme.format(23,"CSE",2,11);assertEquals("230041211",id);var parts=StudentIdScheme.parse(id).orElseThrow();assertEquals(23,parts.batch());assertEquals("CSE",parts.department());assertEquals(2,parts.section());assertEquals(11,parts.roll());assertTrue(StudentIdScheme.matchesScope(id,"CSE","B"));}
 @Test void rejectsUnsupportedCodes(){assertTrue(StudentIdScheme.parse("CSE1A001").isEmpty());assertTrue(StudentIdScheme.parse("240041261").isEmpty());assertThrows(IllegalArgumentException.class,()->StudentIdScheme.format(23,"CSE",2,61));}
}
