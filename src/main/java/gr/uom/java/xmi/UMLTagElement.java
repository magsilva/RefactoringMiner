package gr.uom.java.xmi;

import java.util.ArrayList;
import java.util.List;

import gr.uom.java.xmi.diff.CodeRange;

public class UMLTagElement implements LocationInfoProvider {
	private LocationInfo locationInfo;
	private String tagName;
	private List<UMLDocElement> fragments;
	
	public UMLTagElement(String tagName, LocationInfo locationInfo) {
		this.locationInfo = locationInfo;
		this.tagName = tagName;
		this.fragments = new ArrayList<UMLDocElement>();
	}

	public LocationInfo getLocationInfo() {
		return locationInfo;
	}

	public CodeRange codeRange() {
		return locationInfo.codeRange();
	}

	public void addFragment(UMLDocElement fragment) {
		fragments.add(fragment);
	}

	public String getTagName() {
		return tagName;
	}

	public List<UMLDocElement> getFragments() {
		return fragments;
	}

	public boolean contains(String s) {
		for(UMLDocElement fragment : fragments) {
			if(fragment.getText().contains(s)) {
				return true;
			}
		}
		return false;
	}

	public boolean containsIgnoreCase(String s) {
		for(UMLDocElement fragment : fragments) {
			if(fragment.getText().toLowerCase().contains(s.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((fragments == null) ? 0 : fragments.hashCode());
		result = prime * result + ((tagName == null) ? 0 : tagName.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		UMLTagElement other = (UMLTagElement) obj;
		if (fragments == null) {
			if (other.fragments != null)
				return false;
		} else if (!fragments.equals(other.fragments))
			return false;
		if (tagName == null) {
			if (other.tagName != null)
				return false;
		} else if (!tagName.equals(other.tagName))
			return false;
		return true;
	}
}
